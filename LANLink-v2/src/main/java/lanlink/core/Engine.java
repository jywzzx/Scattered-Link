package lanlink.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * EasyTier 内核进程管理。
 *
 * 设计原则：**不修改 EasyTier 一行代码**，只做「参数生成 + 进程托管 + 日志转译」。
 * 内核本身的 P2P 打洞、加密、跨平台虚拟网卡都由上游负责，我们只解决
 * "小白不会配参数" 这一个问题。
 */
public final class Engine {

    public enum Role { HOST, JOINER }

    private static final int LOG_LIMIT = 400;
    /** 内核 RPC 端口，固定住，`easytier-cli` 才能稳定查到成员 */
    public static final String RPC_PORTAL = "127.0.0.1:15888";

    /** 一个虚拟局域网成员（来自 easytier-cli peer -o json） */
    public static final class Peer {
        public final String ipv4;
        public final String hostname;
        public final String cost;        // Local / p2p / relay
        public final String latMs;
        public final String lossRate;
        public final String tunnel;      // tcp / udp / ...
        public final String natType;

        Peer(String ipv4, String hostname, String cost, String latMs,
             String lossRate, String tunnel, String natType) {
            this.ipv4 = ipv4;
            this.hostname = hostname;
            this.cost = cost;
            this.latMs = latMs;
            this.lossRate = lossRate;
            this.tunnel = tunnel;
            this.natType = natType;
        }

        public boolean isSelf() {
            return cost != null && cost.equalsIgnoreCase("Local");
        }

        public String linkText() {
            if (cost == null) return "—";
            String c = cost.toLowerCase();
            if (c.equals("p2p")) return "直连";
            if (c.contains("relay")) return "中继";
            if (c.equals("local")) return "本机";
            return cost;
        }

        /** "10.144.0.2  joiner  直连 0.44ms" */
        public String display() {
            String name = (hostname == null || hostname.isEmpty()) ? "?" : hostname;
            String lat = (latMs == null || latMs.equals("-")) ? "" : "  " + latMs + "ms";
            return ipv4 + "   " + name + "   " + linkText() + lat;
        }
    }

    private Process proc;
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());

    /**
     * 所有活着的实例。用途：进程被强杀 / 用户直接点 X 时，
     * JVM 关停钩子能把内核子进程一起带走，避免留下孤儿进程占着虚拟网卡和端口。
     */
    private static final List<Engine> ALIVE = Collections.synchronizedList(new ArrayList<>());

    static {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                synchronized (ALIVE) {
                    for (Engine e : new ArrayList<>(ALIVE)) {
                        try { e.stop(); } catch (Exception ignored) { }
                    }
                }
            }, "lanlink-shutdown"));
        } catch (Exception ignored) { }
    }
    private final Set<String> peers = Collections.synchronizedSet(new LinkedHashSet<>());
    private volatile List<Peer> peerCache = Collections.emptyList();
    private volatile String rpcPortal;
    /** 上次启动参数，用于换 IP 后原地重启 */
    private Object[] lastArgs;
    /** 内核 RPC 端口（每个实例随机分配，避免和机器上别的 EasyTier 串数据） */
    public static final String RPC_PORTAL_DEFAULT = "127.0.0.1:15888";
    /**
     * 可选的共享中继节点地址（例如自建中继的 tcp://1.2.3.4:11010）。
     * 直连失败时，流量可经该节点转发。留空表示纯点对点直连。
     * 官方公共节点域名当前无法解析，因此不预置、也不自动挂载。
     */
    private volatile String relayUri;

    public void setRelayUri(String uri) {
        String s = uri == null ? "" : uri.trim();
        if (s.isEmpty()) {
            this.relayUri = null;
        } else if (!s.contains("://")) {
            this.relayUri = "tcp://" + s;
        } else {
            this.relayUri = s;
        }
    }

    public String relayUri() { return relayUri; }

    /**
     * 本次**真正传给内核**的开关与节点，启动成功后即固定不变。
     *
     * 为什么要把它们记下来：界面上的复选框随时可点，但内核一旦起来就不会跟着变。
     * 用户勾了「强制统一中继」、界面提示也全变成中继模式、手机配置也按中继生成，
     * 而电脑端内核仍在按直连跑 —— 两端参数不一致，症状是「手机连上了中继节点，
     * 但列表里只有它自己」。只把界面文案改对不够，必须能对比「界面声称的」与
     * 「内核实际在跑的」，并把差异直接暴露给用户。
     */
    private volatile boolean runningNoP2p;
    private volatile boolean runningUsePublicNodes;
    private volatile String runningRelayUri;
    private volatile List<String> runningPeers = Collections.emptyList();

    /** 内核实际是否处于「只走中继」模式（不是界面复选框的当前状态） */
    public boolean runningNoP2p() { return runningNoP2p; }
    public boolean runningUsePublicNodes() { return runningUsePublicNodes; }
    public String runningRelayUri() { return runningRelayUri; }
    /** 内核实际挂载的全部初始节点 */
    public List<String> runningPeers() { return runningPeers; }

    /** 是否启用社区公共节点（免费、免注册，作为中继兜底） */
    private volatile boolean usePublicNodes = true;
    private volatile List<String> activePublicNodes = Collections.emptyList();

    public void setUsePublicNodes(boolean v) { this.usePublicNodes = v; }
    public List<String> activePublicNodes() { return activePublicNodes; }

    /**
     * 强制统一中继：跳过打洞与点对点直连，全部流量只走 relayUri 指定的那一个节点。
     * 适用场景：手机热点、校园网、运营商大内网（CGNAT）——这些网络里打洞必然失败，
     * 反复尝试直连只会让建链时间变长，不如直接走中继稳定。
     * 代价：多绕一跳，延迟略高。
     */
    private volatile boolean noP2p;

    public void setNoP2p(boolean v) { this.noP2p = v; }
    public boolean noP2p() { return noP2p; }

    /** 本次运行的内核日志文件（位于 %LOCALAPPDATA%\LANLink\logs），启动失败时可能为 null */
    private volatile File logFile;
    private PrintWriter logOut;

    public File logFile() { return logFile; }

    /** 内核日志目录，与 startup.log 同级，便于一起打包发给开发者 */
    public static File logDir() {
        String base = System.getenv("LOCALAPPDATA");
        File dir = new File(base != null && !base.isEmpty() ? base
                : System.getProperty("java.io.tmpdir", "."), "LANLink");
        File logs = new File(dir, "logs");
        if (!logs.isDirectory()) logs.mkdirs();
        return logs;
    }
    private Consumer<String> onLine;
    private volatile boolean running;
    private volatile String selfIp;
    private volatile Role role;

    public boolean isRunning() { return running; }
    public String selfIp() { return selfIp; }
    public Role role() { return role; }
    public String rpcPortal() { return rpcPortal; }

    public List<String> logTail(int n) {
        synchronized (log) {
            int from = Math.max(0, log.size() - n);
            return new ArrayList<>(log.subList(from, log.size()));
        }
    }

    public List<String> peers() {
        List<Peer> detailed = peerCache;
        if (!detailed.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (Peer p : detailed) out.add(p.ipv4);
            return out;
        }
        synchronized (peers) { return new ArrayList<>(peers); }
    }

    /** 结构化成员列表（不含自己） */
    public List<Peer> peersDetailed() {
        return peerCache;
    }

    public void setLineListener(Consumer<String> c) { this.onLine = c; }

    /** 查找内核可执行文件：优先程序目录，其次开发目录 */
    public static File locateCore() {
        String exe = isWindows() ? "easytier-core.exe" : "easytier-core";
        List<File> candidates = new ArrayList<>();
        String home = System.getProperty("lanlink.home");
        if (home != null) candidates.add(new File(home, "engine/" + exe));
        try {
            File jar = new File(Engine.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File base = jar.isFile() ? jar.getParentFile() : jar;
            candidates.add(new File(base, "engine/" + exe));
            candidates.add(new File(base.getParentFile(), "engine/" + exe));
            if (base.getParentFile() != null) {
                candidates.add(new File(base.getParentFile().getParentFile(), "engine/" + exe));
            }
        } catch (Exception ignored) { }
        candidates.add(new File("engine/" + exe));
        candidates.add(new File("LANLink-v2/engine/" + exe));
        for (File f : candidates) if (f.isFile()) return f;
        return null;
    }

    public static File locateCli() {
        File core = locateCore();
        if (core == null) return null;
        File cli = new File(core.getParentFile(), isWindows() ? "easytier-cli.exe" : "easytier-cli");
        return cli.isFile() ? cli : null;
    }

    /**
     * @param role   房主 / 加入者
     * @param inv    邀请信息（房间名与密码都在里面）
     * @param myIp   自己在本虚拟局域网里的 IP
     * @param listenPort 房主监听端口；加入者忽略
     * @param noTun  仅调试用：不创建虚拟网卡（无需管理员权限）
     */
    public synchronized void start(Role role, Invite inv, String myIp, int listenPort, boolean noTun) throws Exception {
        if (running) throw new IllegalStateException("虚拟局域网连接已存在");
        // 防御：调用方若把 null 传进来，给一句人能读懂的话，而不是 "parameter2 is null"
        if (inv == null) throw new IllegalArgumentException("内部错误：邀请信息为空，请重新创建房间");
        if (myIp == null || myIp.isEmpty()) throw new IllegalArgumentException("内部错误：本机虚拟 IP 地址为空");
        if (listenPort <= 0 || listenPort > 65535) throw new IllegalArgumentException("内部错误：监听端口无效");
        lastArgs = new Object[]{role, inv, myIp, listenPort, noTun};

        File core = locateCore();
        if (core == null) throw new IllegalStateException("未找到内核文件 easytier-core.exe（应位于 engine 目录下）");

        List<String> cmd = new ArrayList<>();
        cmd.add(core.getAbsolutePath());
        cmd.add("--network-name");   cmd.add(inv.roomName());
        cmd.add("--network-secret"); cmd.add(inv.secret);
        cmd.add("-i");               cmd.add(myIp + "/24");
        cmd.add("--hostname");       cmd.add("lanlink-" + (role == Role.HOST ? "host" : NetInfo.hostName().replaceAll("[^\\w\\-]", "")));
        // RPC 端口用随机空闲端口，而不是固定 15888：
        // 机器上若还有别的 EasyTier 实例/程序占着 15888，cli 会查到别人的数据，成员列表就串了。
        rpcPortal = "127.0.0.1:" + freePort();
        cmd.add("-r"); cmd.add(rpcPortal);

        if (role == Role.HOST) {
            cmd.add("-l"); cmd.add("tcp://0.0.0.0:" + listenPort);
            cmd.add("-l"); cmd.add("udp://0.0.0.0:" + listenPort);
        } else {
            // 加入方也用随机端口，且与房主端口不同，避免同机多实例撞端口
            cmd.add("-l"); cmd.add("tcp://0.0.0.0:" + listenPort);
            cmd.add("-l"); cmd.add("udp://0.0.0.0:" + listenPort);
            // 优先直连房主
            cmd.add("-p"); cmd.add("tcp://" + inv.host + ":" + inv.port);
            cmd.add("-p"); cmd.add("udp://" + inv.host + ":" + inv.port);
        }
        // 可选的中继节点：直连失败时经其转发
        String relay = relayUri;
        if (relay != null && !relay.isEmpty()) {
            cmd.add("-p"); cmd.add(relay);
        }
        // 强制统一中继：设 LANLINK_NO_P2P=1，或调用方把 noP2p 置真。
        // 先算出来，因为「要不要挂公共节点」取决于它。
        boolean relayOnly = "1".equals(System.getenv("LANLINK_NO_P2P"))
                || noP2p
                || "1".equals(System.getProperty("lanlink.noP2p"));

        // 社区公共节点：双方无法点对点直连时的中继通道（免费、免注册）。
        // 强制中继模式下必须只连一个节点：多挂节点会让两端落到不同通道，
        // 表现就是「都显示在线但互相看不见」。此处强制收敛，不依赖调用方是否传对。
        if (usePublicNodes && !relayOnly) {
            List<String> nodes = Diagnostics.reachablePublicNodes(3);
            for (String n : nodes) {
                cmd.add("-p"); cmd.add(n);
            }
            activePublicNodes = nodes;
        } else {
            activePublicNodes = new ArrayList<>();
        }
        // Windows 专属：把物理网卡上的 UDP 广播转发进虚拟网络。
        // 默认**关闭** —— 它依赖 WinDivert 内核驱动，国产杀软/Defender 容易拦截，
        // 一旦被拦内核直接起不来。需要"游戏自动发现房间"时设环境变量 LANLINK_BROADCAST=1。
        if (isWindows() && "1".equals(System.getenv("LANLINK_BROADCAST"))) {
            cmd.add("--enable-udp-broadcast-relay");
            cmd.add("true");
        }
        // 延迟优先：多条路径时挑最快的那条，联机游戏体感更好
        cmd.add("--latency-first");
        cmd.add("true");
        // 强制只用中继：设 LANLINK_NO_P2P=1 可禁用点对点直连与打洞。
        // 用途有二：
        //   1) 在对称型 NAT 且端口未映射的网络里，直连尝试会反复超时，
        //      直接走中继能更快建链（延迟略高但稳定）。
        //   2) 自动化测试时用来可靠地模拟「异地」场景 ——
        //      同一台机器上的两个实例总会自动建立直连，测不出中继路径。
        if (relayOnly) {
            cmd.add("--disable-p2p");
            cmd.add("true");
            cmd.add("--disable-udp-hole-punching");
            cmd.add("true");
            cmd.add("--disable-tcp-hole-punching");
            cmd.add("true");
        }
        if (noTun) cmd.add("--no-tun");

        // 记下「实际生效」的这一组参数。用户随后改界面不会影响内核，
        // 界面据此判断「显示的模式」与「真实跑的模式」是否已经分叉。
        runningNoP2p = relayOnly;
        // 与上面的收敛保持一致：强制中继时内核确实没有挂公共节点
        runningUsePublicNodes = usePublicNodes && !relayOnly;
        runningRelayUri = relayUri;
        List<String> rp = new ArrayList<>();
        for (int i = 0; i < cmd.size() - 1; i++) {
            if ("-p".equals(cmd.get(i))) rp.add(cmd.get(i + 1));
        }
        runningPeers = Collections.unmodifiableList(rp);

        this.role = role;
        this.selfIp = myIp;
        log.clear();
        peers.clear();
        openLogFile();
        append("启动参数: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(core.getParentFile());
        proc = pb.start();
        running = true;
        ALIVE.add(this);
        pump(proc);
        return;
    }

    private void pump(Process p) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    append(line);
                }
            } catch (Exception ignored) {
            } finally {
                boolean expected = !running;
                running = false;
                if (!expected) append("⚠ 内核进程已退出");
                closeLogFile();
            }
        }, "easytier-log");
        t.setDaemon(true);
        t.start();
    }

    private void append(String raw) {
        String line = stripAnsi(raw);
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        synchronized (log) {
            log.add(line);
            if (log.size() > LOG_LIMIT) log.remove(0);
            PrintWriter w = logOut;
            if (w != null) w.println(stamp + " " + line);
        }
        harvestPeers(line);
        Consumer<String> cb = onLine;
        if (cb != null) {
            try { cb.accept(line); } catch (Exception ignored) { }
        }
    }

    /**
     * 内核日志落盘。
     * 只放内存（此前只有 400 行 logTail）导致事后无法判断「走的是直连还是中继、
     * 丢包多少、卡在哪一步」——真出问题时日志早已被覆盖或随进程消失。
     */
    private void openLogFile() {
        closeLogFile();
        synchronized (log) {
            try {
                File dir = logDir();
                pruneLogs(dir);
                String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
                logFile = new File(dir, "engine-" + ts + ".log");
                logOut = new PrintWriter(new OutputStreamWriter(
                        new FileOutputStream(logFile, true), StandardCharsets.UTF_8), true);
                logOut.println("# LANLink 内核日志  "
                        + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            } catch (Exception e) {
                logFile = null;
                logOut = null;
            }
        }
    }

    private void closeLogFile() {
        PrintWriter w = logOut;
        logOut = null;
        if (w != null) {
            try { w.flush(); w.close(); } catch (Exception ignored) { }
        }
    }

    /** 只保留最近 10 份，避免长期使用把磁盘占满 */
    private static void pruneLogs(File dir) {
        File[] fs = dir.listFiles((d, n) -> n.startsWith("engine-") && n.endsWith(".log"));
        if (fs == null || fs.length < 10) return;
        java.util.Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
        for (int i = 0; i < fs.length - 9; i++) {
            try { fs[i].delete(); } catch (Exception ignored) { }
        }
    }

    /** 从日志里抓 10.144.0.x 的对端地址（不依赖内核日志格式，只要出现就算） */
    private void harvestPeers(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("10\\.144\\.0\\.(\\d{1,3})").matcher(line);
        while (m.find()) {
            String ip = "10.144.0." + m.group(1);
            if (!ip.equals(selfIp)) peers.add(ip);
        }
    }

    /**
     * 内核自身探测到的网络信息。
     * 比外部 STUN 更权威：它反映的是内核实际使用的出口与 NAT ���型。
     */
    public static final class StunInfo {
        public final String publicIp;
        public final String udpNatType;
        public final String tcpNatType;

        StunInfo(String publicIp, String udpNatType, String tcpNatType) {
            this.publicIp = publicIp;
            this.udpNatType = udpNatType;
            this.tcpNatType = tcpNatType;
        }

        /** 是否属于打洞困难的 NAT 类型 */
        public boolean isHardNat() {
            String s = (udpNatType == null ? "" : udpNatType).toLowerCase();
            return s.contains("symmetric") || s.contains("sym");
        }
    }

    /** 读取内核的 STUN 探测结果（公网 IP 与 NAT 类型） */
    public StunInfo stunInfo() {
        String text = runCli("stun");
        if (text == null) return null;
        String ip = "";
        java.util.regex.Matcher pm = java.util.regex.Pattern
                .compile("\"(\\d+\\.\\d+\\.\\d+\\.\\d+)\"").matcher(text);
        if (pm.find()) ip = pm.group(1);
        String udpNat = match1(text, "udp_nat_type:\\s*(\\w+)");
        String tcpNat = match1(text, "tcp_nat_type:\\s*(\\w+)");
        if (ip.isEmpty() && udpNat == null) return null;
        return new StunInfo(ip, udpNat, tcpNat);
    }

    /** 路由器 UPnP 端口映射是否成功（失败则公网无法直连本机，只能走中继） */
    public boolean portMappingOk() {
        String text = runCli("mapped-listener", "list");
        if (text == null) return false;
        return !text.contains("response: []") && text.contains("tcp://");
    }

    private static String match1(String text, String regex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** 执行一个 cli 子命令并返回其输出 */
    private String runCli(String... args) {
        File cli = locateCli();
        String portal = rpcPortal;
        if (cli == null || portal == null) return null;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(cli.getAbsolutePath());
            cmd.add("-p");
            cmd.add(portal);
            for (String a : args) cmd.add(a);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(cli.getParentFile());
            Process p = pb.start();
            String text;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                text = sb.toString();
            }
            if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
            return text;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 主动刷新成员列表：调 easytier-cli 的 JSON 输出。
     * 之前是正则抓 `10.144.0.x`，只能拿到 IP、拿不到延迟/链路类型；
     * 换成 `-o json` 后能拿到 ipv4 / hostname / cost / lat_ms / loss_rate / tunnel_proto。
     */
    public void refreshPeers() {
        File cli = locateCli();
        String portal = rpcPortal;
        if (cli == null || !running || portal == null) return;
        try {
            ProcessBuilder pb = new ProcessBuilder(cli.getAbsolutePath(),
                    "-p", portal, "-o", "json", "peer");
            pb.redirectErrorStream(true);
            pb.directory(cli.getParentFile());
            Process p = pb.start();
            String text;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                text = sb.toString();
            }
            if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
            peerCache = parsePeers(text);
        } catch (Exception ignored) {
            // 查询失败不影响隧道本身，保留上一次结果
        }
    }

    public static List<Peer> parsePeers(String text) {
        int a = text.indexOf('[');
        int b = text.lastIndexOf(']');
        if (a < 0 || b <= a) return Collections.emptyList();
        Object root;
        try {
            root = Json.parse(text.substring(a, b + 1));
        } catch (Exception e) {
            return Collections.emptyList();
        }
        if (!(root instanceof List)) return Collections.emptyList();

        List<Peer> out = new ArrayList<>();
        for (Object o : (List<?>) root) {
            if (!(o instanceof java.util.Map)) continue;
            java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
            String ip = str(m.get("ipv4"));
            if (ip == null || ip.isEmpty()) continue;
            // 只保留本虚拟网段内的成员：公共中继节点不在本网段，须排除
            if (!ip.startsWith(Invite.VNET_PREFIX)) continue;
            Peer peer = new Peer(ip, str(m.get("hostname")), str(m.get("cost")),
                    str(m.get("lat_ms")), str(m.get("loss_rate")),
                    str(m.get("tunnel_proto")), str(m.get("nat_type")));
            if (peer.isSelf()) continue;
            out.add(peer);
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * 换用新的本机虚拟 IP 重启隧道。
     * 用于虚拟 IP 与其他成员冲突时的自愈。
     */
    public synchronized void restartWithIp(String newIp) throws Exception {
        if (lastArgs == null) throw new IllegalStateException("尚未启动，无法重启");
        Object[] a = lastArgs.clone();
        stop();
        Thread.sleep(400);
        start((Role) a[0], (Invite) a[1], newIp, (Integer) a[3], (Boolean) a[4]);
    }

    public synchronized void stop() {
        running = false;
        ALIVE.remove(this);
        Process p = proc;
        proc = null;
        if (p == null) return;
        try {
            p.destroy();
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (Exception ignored) {
            try { p.destroyForcibly(); } catch (Exception ignored2) { }
        }
        if (isWindows()) {
            // 内核可能有子进程，兜底杀一次
            try {
                new ProcessBuilder("taskkill", "/F", "/IM", "easytier-core.exe")
                        .redirectErrorStream(true).start().waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) { }
        }
        closeLogFile();
    }

    static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*m", "");
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 端口是否可绑定（判断 15888 有没有被别人占） */
    static boolean portFree(int port) {
        try (java.net.ServerSocket s = new java.net.ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 向系统要一个空闲端口 */
    static int freePort() {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort();
        } catch (Exception e) {
            return 15888;
        }
    }
}
