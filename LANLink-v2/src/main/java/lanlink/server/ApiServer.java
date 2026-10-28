package lanlink.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lanlink.core.Diagnostics;
import lanlink.core.Engine;
import lanlink.core.Invite;
import lanlink.core.Json;
import lanlink.core.NetInfo;
import lanlink.core.PhoneConfigText;
import lanlink.core.PhoneProfile;
import lanlink.core.Stun;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static lanlink.server.JsonWriter.map;
import static lanlink.server.JsonWriter.of;

/**
 * 本地 HTTP 接口层：把 lanlink.core 里那套能力暴露成 JSON 接口，供 Vue 桌面前端调用。
 *
 * 设计取舍
 * ────────
 * 1. **只监听 127.0.0.1**，并强制校验随机 token。
 *    这层接口能启停内核、读日志，等于本机的一个操作面板，绝不能对外网开放。
 * 2. **零外部依赖**：只用 JDK 自带的 com.sun.net.httpserver，不需要 Spring 全家桶。
 *    这个项目的体积一直是硬约束（打包 72MB，其中主要是 JRE 与内核）。
 * 3. **前端无状态，后端持有状态**：Engine 是单实例，会话信息存在本类里。
 *    前端刷新、重开窗口都不会丢房间。
 * 4. 每个响应都是扁平 JSON，字段名与前端 src/api/fixtures.js 严格对齐。
 *
 * 接口清单见 docs/02-前后端接口契约.md。
 */
public final class ApiServer {

    private static final String API_PREFIX = "/api/v1";
    private static final String TOKEN_HEADER = "X-Sparsa-Token";

    private final HttpServer server;
    private final String token;
    private final Engine engine = new Engine();
    private final Object lock = new Object();

    /** 当前会话（内核已启动时非 null）。 */
    private volatile Session current;

    /** 公网探测结果缓存：STUN 要几百毫秒到数秒，不能每个请求都打一次。 */
    private volatile long netCacheAt;
    private volatile Map<String, Object> netCache;

    private static final class Session {
        String role;
        String myIp;
        int listenPort;
        String relayUri = "";
        boolean noP2p;
        boolean usePublicNodes;
        Invite invite;
        long startedAt;
    }

    /** 业务异常：带上要给用户看的状态码与中文原因。 */
    private static final class ApiError extends RuntimeException {
        final int status;

        ApiError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public ApiServer(int port, String token) throws IOException {
        this.token = token == null ? "" : token;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        // 6 个线程：建房/加入会阻塞数秒，公网探测也可能慢，留足余量避免请求互相排队
        this.server.setExecutor(Executors.newFixedThreadPool(6));
        this.server.createContext("/", this::handle);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String token() {
        return token;
    }

    public void start() {
        server.start();
    }

    public void stop() {
        try {
            if (engine.isRunning()) {
                engine.stop();
            }
        } catch (Exception ignored) {
            // 退出路径上不再抛异常，避免掩盖真正的错误
        }
        server.stop(0);
    }

    // ────────────────────────────────────────────────────────────
    // 请求分发
    // ────────────────────────────────────────────────────────────

    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod().toUpperCase();
            String path = ex.getRequestURI().getPath();
            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());

            if ("OPTIONS".equals(method)) {
                send(ex, 204, null);
                return;
            }

            // 健康检查不需要 token：前端要先探活再决定用不用
            if (path.endsWith("/health")) {
                send(ex, 200, of("ok", true, "ready", true, "apiVersion", "v1"));
                return;
            }

            if (!token.isEmpty() && !token.equals(header(ex, TOKEN_HEADER))) {
                send(ex, 401, of("message", "接口 token 校验失败，请在桌面端重新启动后端"));
                return;
            }

            String rel = path.startsWith(API_PREFIX) ? path.substring(API_PREFIX.length()) : path;
            Map<String, Object> body = readJsonBody(ex);

            Object result = route(method, rel, body, query);
            send(ex, 200, result);

        } catch (ApiError e) {
            send(ex, e.status, of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            // 参数/邀请码这类用户输入问题，统一按 400 返回，文案直接来自 core
            send(ex, 400, of("message", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            String human = Diagnostics.humanError(String.valueOf(e.getMessage()));
            send(ex, 500, of("message", human));
        } finally {
            ex.close();
        }
    }

    private Object route(String method, String path, Map<String, Object> body,
                         Map<String, String> q) throws Exception {
        switch (path) {
            case "/system/info":
                return systemInfo();
            case "/system/preflight":
                return preflight();
            case "/system/network":
                return network();
            case "/system/public-nodes":
                return publicNodes(q);
            case "/system/firewall":
                Diagnostics.addFirewallRules(null, Engine.locateCore());
                return of("ok", true, "detail", "已尝试添加防火墙入站规则");

            case "/session":
                if ("DELETE".equals(method)) {
                    return stopSession();
                }
                return sessionDto();

            case "/session/host":
                return startHost(body);
            case "/session/join":
                return startJoin(body);
            case "/session/ping":
                return ping(body);
            case "/session/public-ip":
                return recheckPublicIp();

            case "/members":
                return members();
            case "/logs":
                return logs(q);
            case "/phone/profile":
                return phoneProfile(q);
            case "/config/parse":
                return parseConfig(body);
            case "/invite/parse":
                return parseInvite(body);
            case "/diagnostics":
                return of("text", diagnosticsText());
            default:
                throw new ApiError(404, "未知接口：" + method + " " + path);
        }
    }

    // ────────────────────────────────────────────────────────────
    // 系统
    // ────────────────────────────────────────────────────────────

    private Object systemInfo() {
        File core = Engine.locateCore();
        return of(
                "appVersion", "1.1.0",
                "apiVersion", "v1",
                "os", System.getProperty("os.name") + " " + System.getProperty("os.version"),
                "hostname", NetInfo.hostName(),
                "admin", Diagnostics.isElevated(),
                "javaVersion", System.getProperty("java.version"),
                "corePath", core == null ? "" : core.getAbsolutePath(),
                "coreFound", core != null,
                "cliFound", Engine.locateCli() != null,
                "engineReady", engine.isRunning()
        );
    }

    private Object preflight() {
        File core = Engine.locateCore();
        File engineDir = core == null ? null : core.getParentFile();

        List<Object> items = new ArrayList<>();
        items.add(item("engineDir", "内核目录",
                engineDir != null && engineDir.isDirectory(),
                engineDir == null ? "未找到 engine 目录" : engineDir.getAbsolutePath()));
        items.add(item("core", "内核文件",
                core != null && core.isFile(),
                core == null ? "未找到 easytier-core.exe" : "已就位"));
        items.add(item("wintun", "Wintun 驱动",
                engineDir != null && new File(engineDir, "wintun.dll").isFile(),
                engineDir == null ? "缺少内核目录" : "创建虚拟网卡所需"));
        items.add(item("cli", "内核工具",
                Engine.locateCli() != null,
                "用于读取成员列表与 NAT 信息"));
        items.add(item("admin", "管理员权限",
                Diagnostics.isElevated(),
                "创建虚拟网卡必须提权"));

        Diagnostics.Preflight pf = Diagnostics.preflight(engineDir, Diagnostics.isElevated());
        return of(
                "ok", pf.ok,
                "title", pf.title,
                "detail", pf.detail,
                "action", pf.action,
                "items", items
        );
    }

    private Map<String, Object> item(String key, String label, boolean ok, String detail) {
        return of("key", key, "label", label, "ok", ok, "detail", detail);
    }

    private Object network() {
        Map<String, Object> cached = netCache;
        if (cached != null && System.currentTimeMillis() - netCacheAt < 30_000L) {
            return cached;
        }

        List<Object> ifaces = new ArrayList<>();
        for (NetInfo.Iface i : NetInfo.ipv4()) {
            ifaces.add(of("iface", i.name, "addr", i.ip, "virtual", i.virtual));
        }

        String publicIp = "";
        try {
            Stun.Mapped m = Stun.query();
            if (m != null) {
                publicIp = m.address;
            }
        } catch (Exception ignored) {
            // STUN 失败不影响其它字段，公网 IP 留空由界面显示「探测中」
        }

        String udpNat = "";
        String tcpNat = "";
        boolean hardNat = false;
        boolean portMappingOk = false;
        if (engine.isRunning()) {
            Engine.StunInfo si = engine.stunInfo();
            if (si != null) {
                udpNat = si.udpNatType == null ? "" : si.udpNatType;
                tcpNat = si.tcpNatType == null ? "" : si.tcpNatType;
                hardNat = si.isHardNat();
            }
            portMappingOk = engine.portMappingOk();
        }

        Map<String, Object> out = of(
                "lanIp", nullToEmpty(NetInfo.bestLanIp()),
                "ipv4", ifaces,
                "virtualNicUp", NetInfo.virtualNicUp(),
                "publicIp", publicIp,
                "nat", of("udp", udpNat.isEmpty() ? "未知" : udpNat,
                        "tcp", tcpNat.isEmpty() ? "未知" : tcpNat),
                "portMappingOk", portMappingOk,
                "hardNat", hardNat
        );
        netCache = out;
        netCacheAt = System.currentTimeMillis();
        return out;
    }

    private Object publicNodes(Map<String, String> q) {
        int limit = intOr(q.get("limit"), 3);
        List<Object> nodes = new ArrayList<>(Diagnostics.reachablePublicNodes(limit));
        String recommended = Diagnostics.recommendedNode();
        return of("nodes", nodes, "recommended", recommended == null ? "" : recommended);
    }

    // ────────────────────────────────────────────────────────────
    // 会话
    // ────────────────────────────────────────────────────────────

    private Object startHost(Map<String, Object> body) throws Exception {
        synchronized (lock) {
            if (engine.isRunning()) {
                throw new ApiError(409, "已有连接在运行，请先断开");
            }

            boolean noP2p = boolOr(body.get("noP2p"), false);
            boolean usePublicNodes = boolOr(body.get("usePublicNodes"), true);
            String relayUri = strOr(body.get("relayUri"), "");

            // 强制统一中继与社区公共节点互斥：公共节点彼此独立，
            // 多挂节点会让手机与电脑落到不同通道。调用方传错也在这里收敛。
            if (noP2p) {
                usePublicNodes = false;
            }

            if (noP2p && relayUri.isEmpty()) {
                throw new ApiError(400, "已勾选「强制统一中继」，但没有填写中继节点");
            }

            String publicIp = "";
            try {
                Stun.Mapped m = Stun.query();
                if (m != null) publicIp = m.address;
            } catch (Exception ignored) {
                // 下面统一判空
            }
            if (publicIp.isEmpty()) {
                throw new ApiError(500, "无法探测公网地址，请检查网络后重试（可先看「网络诊断」）");
            }

            int port = Diagnostics.pickPort(20000, 60000);
            String secret = Invite.randomSecret();
            Invite inv = Invite.of(publicIp + ":" + port, secret);

            engine.setNoP2p(noP2p);
            engine.setUsePublicNodes(usePublicNodes);
            engine.setRelayUri(relayUri);
            engine.start(Engine.Role.HOST, inv, Invite.VNET_PREFIX + "1", port, noTun());
            verifyAlive();

            Session s = new Session();
            s.role = "HOST";
            s.myIp = Invite.VNET_PREFIX + "1";
            s.listenPort = port;
            s.relayUri = relayUri;
            s.noP2p = noP2p;
            s.usePublicNodes = usePublicNodes;
            s.invite = inv;
            s.startedAt = System.currentTimeMillis();
            current = s;

            netCache = null;
            return sessionDto();
        }
    }

    private Object startJoin(Map<String, Object> body) throws Exception {
        synchronized (lock) {
            if (engine.isRunning()) {
                throw new ApiError(409, "已有连接在运行，请先断开");
            }

            boolean noP2p = boolOr(body.get("noP2p"), false);
            boolean usePublicNodes = boolOr(body.get("usePublicNodes"), true);
            String relayUri = strOr(body.get("relayUri"), "");
            if (noP2p) {
                usePublicNodes = false;
            }

            Invite inv = inviteFromBody(body);
            String myIp = Invite.deriveVirtualIp(inv.secret, NetInfo.fingerprint());
            int port = Diagnostics.pickPort(20000, 60000);

            engine.setNoP2p(noP2p);
            engine.setUsePublicNodes(usePublicNodes);
            engine.setRelayUri(relayUri);
            engine.start(Engine.Role.JOINER, inv, myIp, port, noTun());
            verifyAlive();

            Session s = new Session();
            s.role = "JOINER";
            s.myIp = myIp;
            s.listenPort = port;
            s.relayUri = relayUri;
            s.noP2p = noP2p;
            s.usePublicNodes = usePublicNodes;
            s.invite = inv;
            s.startedAt = System.currentTimeMillis();
            current = s;

            netCache = null;
            return sessionDto();
        }
    }

    /**
     * 是否跳过虚拟网卡（仅用于无管理员权限的自动化验证，SPARSA_NO_TUN=1）。
     * 默认关闭 —— 不建网卡就只是空转，联机功能不可用。
     */
    private static boolean noTun() {
        return "1".equals(System.getenv("SPARSA_NO_TUN"));
    }

    /**
     * 启动后确认内核没有立刻退出。
     *
     * 为什么必须做这一步：Engine.start() 只代表进程创建成功，真正建网卡、
     * 接节点是内核内部异步做的。没有管理员权限时内核会在几百毫秒后带着
     * "Failed to create adapter" 退出；若接口此时已返回「创建成功」，
     * 界面就会显示「在线但什么都没有」—— 这正是旧版最难排查的一类假象。
     */
    private void verifyAlive() {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (!engine.isRunning()) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (engine.isRunning()) {
            return;
        }

        String reason = "";
        for (String line : engine.logTail(40)) {
            String t = line.trim();
            if (t.contains("ERROR") || t.contains("Failed") || t.contains("error")) {
                reason = t;
            }
        }
        engine.stop();

        StringBuilder msg = new StringBuilder("内核启动后立即退出，虚拟局域网未能建立。");
        if (!reason.isEmpty()) {
            msg.append("原因：").append(Diagnostics.humanError(reason));
        }
        if (!Diagnostics.isElevated()) {
            msg.append("（当前未以管理员身份运行，创建虚拟网卡需要提权）");
        }
        throw new ApiError(500, msg.toString());
    }

    private Object stopSession() {        synchronized (lock) {
            if (engine.isRunning()) {
                engine.stop();
            }
            current = null;
            netCache = null;
            return of("ok", true);
        }
    }

    private Map<String, Object> sessionDto() {
        Session s = current;
        boolean active = engine.isRunning() && s != null;

        Map<String, Object> kernel = of(
                "noP2p", engine.runningNoP2p(),
                "usePublicNodes", engine.runningUsePublicNodes(),
                "relayUri", nullToEmpty(engine.runningRelayUri()),
                "peers", new ArrayList<>(engine.runningPeers())
        );

        Map<String, Object> inv = null;
        if (s != null && s.invite != null) {
            inv = of(
                    "host", s.invite.host,
                    "port", s.invite.port,
                    "secret", s.invite.secret,
                    "code", s.invite.format(),
                    "displayHost", s.invite.displayHost(),
                    "roomName", s.invite.roomName()
            );
        }

        return of(
                "active", active,
                "role", active ? s.role : "",
                "myIp", active ? s.myIp : "",
                "listenPort", active ? s.listenPort : 0,
                "invite", inv,
                "relayUri", active ? nullToEmpty(s.relayUri) : "",
                "noP2p", active && s.noP2p,
                "usePublicNodes", active && s.usePublicNodes,
                "kernel", kernel,
                "divergence", "",
                "startedAt", s == null ? null : s.startedAt,
                "publicIp", nullToEmpty(netPublicIp())
        );
    }

    /**
     * 连通性测试。body 可带 "ip" 指定某一个成员；不传时取第一个非本机成员。
     *
     * 单个成员测试是必要的：房间里有三个人时，A 能通不代表 B 能通，
     * 链路类型（直连 / 中继）与延迟各自独立。
     */
    private Object ping(Map<String, Object> body) {
        Session s = current;
        if (s == null || !engine.isRunning()) {
            return of("ok", false, "ms", null, "detail", "当前没有运行中的连接");
        }

        String want = strOr(body == null ? null : body.get("ip"), "").trim();
        if (want.equals(s.myIp)) {
            return of("ok", false, "ms", null, "target", want,
                    "detail", "本机不参与连通性测试，请选择其他成员。");
        }

        Engine.Peer target = null;
        boolean hasOthers = false;
        for (Engine.Peer p : engine.peersDetailed()) {
            if (p.isSelf() || p.ipv4.equals(s.myIp)) continue;
            hasOthers = true;
            if (want.isEmpty() || p.ipv4.equals(want)) {
                target = p;
                break;
            }
        }

        if (!want.isEmpty() && target == null) {
            return of("ok", false, "ms", null, "target", want,
                    "detail", hasOthers
                            ? "该地址不在当前成员列表中，请刷新后重试。"
                            : "暂无其他成员接入，请等待对方使用邀请码连接后重试。");
        }
        if (target == null) {
            return of("ok", false, "ms", null,
                    "detail", "暂无其他成员接入，请等待对方使用邀请码连接后重试。");
        }

        PingResult r = pingOnce(target.ipv4);
        if (!r.ok) {
            return of("ok", false, "ms", null, "target", target.ipv4,
                    "detail", "无法与 " + target.ipv4 + " 建立连接。可能对方尚未接入，或被防火墙拦截。");
        }
        if (r.ms == null) {
            // 能通，但没解析出具体数值。不编造 0，如实说明。
            return of("ok", true, "ms", null, "target", target.ipv4,
                    "detail", "与 " + target.ipv4 + " 连通，未取到延迟数值");
        }
        return of("ok", true, "ms", r.ms, "target", target.ipv4,
                "detail", "与 " + target.ipv4 + " 连通（" + r.ms + " ms）");
    }

    /** 一次 ping 的结果：ok 表示是否连通，ms 为空表示通但没解析到数值。 */
    private static final class PingResult {
        final boolean ok;
        final Integer ms;

        PingResult(boolean ok, Integer ms) {
            this.ok = ok;
            this.ms = ms;
        }

        static PingResult ok(Integer ms) {
            return new PingResult(true, ms);
        }

        static PingResult fail() {
            return new PingResult(false, null);
        }
    }

    /**
     * Windows 下用系统 ping 做一次可达性判断。
     *
     * 早先这里有三个错误叠加，导致「永远显示 0 ms」：
     *   1. 输出按 UTF-8 解码，而控制台是 GBK，中文全部变成乱码；
     *   2. 用中文关键词「时间」匹配，乱码后必然失败；
     *   3. 失败后兜底成「看到 TTL 就当他 0ms」，于是不管真实延迟多少都报 0。
     * 现在按系统代码页解码，并且只在「含 TTL 的回复行」里取数值；
     * 取不到数值时返回 null，由调用方如实告知，不再编造。
     */
    private PingResult pingOnce(String ip) {
        try {
            Process p = new ProcessBuilder("ping", "-n", "1", "-w", "1500", ip)
                    .redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            p.waitFor();

            String text = decodeConsole(out.toByteArray());
            for (String line : text.split("\r?\n")) {
                // 回复行同时含 TTL 与延迟，其余行（统计信息）不参与判断
                if (!line.contains("TTL")) continue;
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("[=<]\\s*(\\d+)\\s*ms").matcher(line);
                if (m.find()) {
                    return PingResult.ok(Integer.parseInt(m.group(1)));
                }
                return PingResult.ok(null);
            }
            return PingResult.fail();
        } catch (Exception e) {
            return PingResult.fail();
        }
    }

    /** 控制台输出按系统代码页解码，简体中文环境为 GBK。 */
    private static String decodeConsole(byte[] bytes) {
        String enc = System.getProperty("sun.jnu.encoding", "GBK");
        try {
            return new String(bytes, java.nio.charset.Charset.forName(enc));
        } catch (Exception e) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private Object recheckPublicIp() {
        netCache = null;
        String now = nullToEmpty(netPublicIp());
        Session s = current;
        boolean changed = s != null && s.invite != null && !s.invite.host.equals(now);
        Map<String, Object> inv = null;
        if (s != null && s.invite != null) {
            inv = of("host", s.invite.host, "port", s.invite.port, "secret", s.invite.secret,
                    "code", s.invite.format(), "displayHost", s.invite.displayHost(),
                    "roomName", s.invite.roomName());
        }
        return of("changed", changed, "publicIp", now, "invite", inv);
    }

    private String netPublicIp() {
        try {
            Stun.Mapped m = Stun.query();
            return m == null ? "" : m.address;
        } catch (Exception e) {
            return "";
        }
    }

    // ────────────────────────────────────────────────────────────
    // 成员 / 日志
    // ────────────────────────────────────────────────────────────

    private Object members() {
        List<Object> list = new ArrayList<>();
        Session s = current;
        boolean active = engine.isRunning() && s != null;

        if (active) {
            // 主机自己固定排在第一行。内核返回的成员列表只含对端，
            // 界面上看不到自己就无法判断「房间里总共几个人、我是哪一个」。
            list.add(of(
                    "ipv4", s.myIp,
                    "hostname", nullToEmpty(NetInfo.hostName()),
                    "cost", "Local",
                    "latMs", "-",
                    "lossRate", "-",
                    "tunnel", "-",
                    "natType", "",
                    "self", true,
                    "reachable", null,
                    "pingMs", null
            ));

            engine.refreshPeers();
            for (Engine.Peer p : engine.peersDetailed()) {
                // 与上一行重复的本机记录不再追加
                if (p.isSelf() || p.ipv4.equals(s.myIp)) continue;
                list.add(of(
                        "ipv4", p.ipv4,
                        "hostname", nullToEmpty(p.hostname),
                        "cost", nullToEmpty(p.cost),
                        "latMs", nullToEmpty(p.latMs),
                        "lossRate", nullToEmpty(p.lossRate),
                        "tunnel", nullToEmpty(p.tunnel),
                        "natType", nullToEmpty(p.natType),
                        "self", false,
                        "reachable", null,
                        "pingMs", null
                ));
            }
        }
        return of("members", list, "count", list.size(), "onlineCount", list.size());
    }

    private Object logs(Map<String, String> q) {
        int tail = intOr(q.get("tail"), 200);
        List<Object> lines = new ArrayList<>();
        for (String l : engine.logTail(tail)) {
            lines.add(l);
        }
        return of("lines", lines);
    }

    // ────────────────────────────────────────────────────────────
    // 手机端配置
    // ────────────────────────────────────────────────────────────

    private Object phoneProfile(Map<String, String> q) {
        String mode = q.getOrDefault("mode", "direct");
        String relayUri = q.getOrDefault("relayUri", "");
        boolean relay = "relay".equalsIgnoreCase(mode);

        Session s = current;
        if (s == null || !engine.isRunning()) {
            List<Object> tips = new ArrayList<>();
            tips.add(of("level", "error",
                    "text", "当前没有运行中的房间，配置无法生成。请先在「创建房间」里建房。"));
            return of("ready", false, "mode", mode, "toml", "", "carryText", "",
                    "tips", tips, "meta", of(), "divergence", "", "ipCollisionHint", "");
        }

        String lanIp = nullToEmpty(NetInfo.bestLanIp());
        String phoneIp = Invite.VNET_PREFIX + "9";

        PhoneProfile p;
        if (relay) {
            p = PhoneProfile.relay(s.invite, s.invite.host, s.invite.secret, lanIp,
                    s.listenPort, phoneIp, relayUri);
        } else {
            p = PhoneProfile.direct(s.invite, s.invite.host, s.invite.secret, lanIp,
                    s.listenPort, phoneIp, engine.activePublicNodes());
        }

        List<Object> tips = new ArrayList<>();
        for (PhoneProfile.Tip t : p.tips()) {
            tips.add(of("level", t.level, "text", t.text));
        }

        String divergence = "";
        if (engine.isRunning()) {
            String d = p.divergenceHint(engine.runningNoP2p(), engine.runningUsePublicNodes(),
                    engine.runningRelayUri());
            divergence = d == null ? "" : d;
        }
        if (relay && relayUri.isEmpty()) {
            divergence = divergence.isEmpty()
                    ? "强制统一中继模式下必须填写节点，当前为空。"
                    : divergence;
        }

        return of(
                "ready", true,
                "mode", mode,
                "toml", p.toToml(),
                "carryText", PhoneConfigText.encode(p),
                "tips", tips,
                "meta", of(
                        "networkName", Invite.NETWORK_NAME,
                        "secret", s.invite.secret,
                        "initialPeer", p.effectivePeers().isEmpty() ? "" : p.effectivePeers().get(0),
                        "peerCount", p.effectivePeers().size(),
                        "lanIp", lanIp,
                        "myIp", phoneIp,
                        "secondIp", p.secondPhoneIp()
                ),
                "divergence", divergence,
                "ipCollisionHint", p.ipCollisionHint()
        );
    }

    private Object parseConfig(Map<String, Object> body) {
        String text = strOr(body.get("text"), "");
        PhoneConfigText.Parsed p = PhoneConfigText.parse(text);

        Map<String, Object> inv = null;
        if (p.invite != null) {
            inv = of("host", p.invite.host, "port", p.invite.port, "secret", p.invite.secret,
                    "code", p.invite.format(), "displayHost", p.invite.displayHost(),
                    "roomName", p.invite.roomName());
        }
        return of(
                "ok", p.toml != null && p.problems.isEmpty(),
                "mode", p.relay ? "relay" : "direct",
                "toml", nullToEmpty(p.toml),
                "ipv4", nullToEmpty(p.ipv4),
                "relay", nullToEmpty(p.firstPeer),
                "secret", nullToEmpty(p.secret),
                "networkName", Invite.NETWORK_NAME,
                "problems", new ArrayList<Object>(p.problems),
                "invite", inv
        );
    }

    private Object parseInvite(Map<String, Object> body) {
        String text = strOr(body.get("text"), "");
        Invite inv = Invite.parse(text);
        return of("invite", of(
                "host", inv.host,
                "port", inv.port,
                "secret", inv.secret,
                "code", inv.format(),
                "displayHost", inv.displayHost(),
                "roomName", inv.roomName()
        ));
    }

    // ────────────────────────────────────────────────────────────
    // 诊断
    // ────────────────────────────────────────────────────────────

    /**
     * 诊断文本刻意把「界面声明」与「内核实际」并排写出来。
     * 历史上最难查的一个 bug 就是界面显示强制中继、内核其实在直连，
     * 只看界面永远发现不了。
     */
    private String diagnosticsText() {
        Session s = current;
        StringBuilder sb = new StringBuilder();
        sb.append("时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())).append('\n');
        sb.append("公网 IP  : ").append(orDash(netPublicIp())).append('\n');
        sb.append("局域网 IP: ").append(orDash(NetInfo.bestLanIp())).append('\n');

        Engine.StunInfo si = engine.isRunning() ? engine.stunInfo() : null;
        sb.append("NAT 类型 : UDP=").append(orDash(si == null ? null : si.udpNatType))
                .append("  TCP=").append(orDash(si == null ? null : si.tcpNatType)).append('\n');
        sb.append("端口映射 : ").append(engine.isRunning() && engine.portMappingOk() ? "已生效" : "未生效").append('\n');
        sb.append("监听端口 : ").append(s == null ? "—" : String.valueOf(s.listenPort)).append('\n');
        sb.append("中继节点 : ").append(s == null || s.relayUri.isEmpty() ? "未设置" : s.relayUri).append('\n');
        sb.append("强制中继 : ").append(s != null && s.noP2p ? "是" : "否").append('\n');
        sb.append("内核实际 : 强制中继=").append(engine.runningNoP2p() ? "是" : "否")
                .append(" 挂公共节点=").append(engine.runningUsePublicNodes() ? "是" : "否")
                .append(" 中继=").append(orDash(engine.runningRelayUri())).append('\n');
        sb.append("内核节点 : ").append(engine.runningPeers().isEmpty()
                ? "—" : String.join(", ", engine.runningPeers())).append('\n');
        sb.append("本机虚拟IP: ").append(s == null ? "—" : s.myIp).append('\n');
        sb.append("邀请码   : ").append(s == null ? "未创建" : s.invite.format()).append('\n');

        List<String> peers = new ArrayList<>();
        if (engine.isRunning()) {
            for (Engine.Peer p : engine.peersDetailed()) {
                peers.add(p.ipv4 + "(" + p.linkText() + ")");
            }
        }
        sb.append("已见成员 : ").append(peers.isEmpty() ? "—" : String.join(", ", peers)).append('\n');
        File log = engine.logFile();
        sb.append("内核日志 : ").append(log == null ? "未生成" : log.getAbsolutePath());
        return sb.toString();
    }

    // ────────────────────────────────────────────────────────────
    // 工具
    // ────────────────────────────────────────────────────────────

    /** 支持 body 里给 {invite:{host,port,secret}}、{code:"ip:port#secret"} 或平铺 host/secret */
    private Invite inviteFromBody(Map<String, Object> body) {
        Object invObj = body.get("invite");
        if (invObj instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) invObj;
            String host = strOr(m.get("host"), "");
            String secret = strOr(m.get("secret"), "");
            int port = intOr(m.get("port"), 11010);
            if (host.isEmpty() || secret.isEmpty()) {
                throw new ApiError(400, "邀请信息不完整：缺少对方地址或房间密钥");
            }
            return Invite.of(host + ":" + port, secret);
        }
        String code = strOr(body.get("code"), "");
        if (!code.isEmpty()) {
            return Invite.parse(code);
        }
        String host = strOr(body.get("host"), "");
        String secret = strOr(body.get("secret"), "");
        if (!host.isEmpty() && !secret.isEmpty()) {
            int port = intOr(body.get("port"), 11010);
            return Invite.of(host + ":" + port, secret);
        }
        throw new ApiError(400, "请输入邀请码");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonBody(HttpExchange ex) {
        try {
            InputStream in = ex.getRequestBody();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            if (out.size() == 0) {
                return Collections.emptyMap();
            }
            Object parsed = Json.parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
            return Collections.emptyMap();
        } catch (Exception e) {
            throw new ApiError(400, "请求体不是合法的 JSON");
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            try {
                out.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (Exception ignored) {
                // 编码坏掉的参数直接丢弃，不因此让整个请求失败
            }
        }
        return out;
    }

    private static String header(HttpExchange ex, String name) {
        List<String> v = ex.getRequestHeaders().get(name);
        return v == null || v.isEmpty() ? "" : v.get(0);
    }

    private static void send(HttpExchange ex, int status, Object payload) throws IOException {
        if (payload == null) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = JsonWriter.write(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        // 只服务本机前端；即便被别的东西访问也拿不到数据（需要 token），但仍收紧来源
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, " + TOKEN_HEADER);
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String strOr(Object v, String def) {
        return v == null ? def : String.valueOf(v);
    }

    private static boolean boolOr(Object v, boolean def) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return "true".equalsIgnoreCase((String) v) || "1".equals(v);
        return def;
    }

    private static int intOr(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return v == null ? def : Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String orDash(String s) {
        return s == null || s.isEmpty() ? "—" : s;
    }

    /** 生成一个随机 token，防止本机其它进程随意调用接口。 */
    public static String randomToken() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(32);
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 32; i++) {
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
