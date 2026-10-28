package lanlink.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 启动前预检、端口选择与防火墙规则。
 * 目的只有一个：让失败变成一句人话 + 一个明确动作，而不是一堆英文日志。
 */
public final class Diagnostics {

    /** 预检结论 */
    public static final class Preflight {
        public final boolean ok;
        public final String title;
        public final String detail;
        public final String action;

        Preflight(boolean ok, String title, String detail, String action) {
            this.ok = ok;
            this.title = title;
            this.detail = detail;
            this.action = action;
        }
    }

    private Diagnostics() { }

    /**
     * 社区公开的共享节点（EasyTier 生态，志愿者提供）。
     * 作用是「牵线 + 中继」：双方无法点对点直连时，流量经这些节点转发。
     * 免费、无需注册；中继方只能看到端到端加密后的密文。
     */
    public static final String[] PUBLIC_NODES = {
            "tcp://225284.xyz:11010",
            "tcp://183.230.36.171:11010",
            "tcp://easytier.weiai.org.cn:11010",
            "tcp://ros.scpsl.com.cn:11010",
            "tcp://et.basd1.de:11010",
            "wss://et.chinokou.cn",
    };

    /**
     * 并发探测公共节点，返回可达的若干个。
     * 单个节点超时 2.5 秒，整体不超过 3 秒，不拖慢建房。
     */
    public static java.util.List<String> reachablePublicNodes(int want) {
        final List<String> ok = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<Thread> ts = new ArrayList<>();
        for (String n : PUBLIC_NODES) {
            Thread t = new Thread(() -> {
                if (probe(n)) {
                    synchronized (ok) {
                        if (ok.size() < want) ok.add(n);
                    }
                }
            }, "probe-" + n);
            t.setDaemon(true);
            ts.add(t);
            t.start();
        }
        for (Thread t : ts) {
            try { t.join(3000); } catch (InterruptedException ignored) { }
        }
        synchronized (ok) {
            return new ArrayList<>(ok);
        }
    }

    /** 探测一个节点地址是否可达（TCP） */
    static boolean probe(String node) {
        try {
            java.net.URI u = new java.net.URI(node);
            String scheme = u.getScheme() == null ? "tcp" : u.getScheme();
            String host = u.getHost();
            if (host == null) return false;
            int port = u.getPort();
            if (port <= 0) port = ("wss".equals(scheme) || "https".equals(scheme)) ? 443 : 11010;
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress(host, port), 2500);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 推荐一个公共节点：按 PUBLIC_NODES 的固定顺序取第一个可达的。
     *
     * 固定顺序很关键。「强制统一中继」要求三台设备挂**同一个**节点，
     * 若各自随机挑可达节点，很可能各落一处 —— 公共节点彼此独立，
     * 不在同一节点上的两台设备之间没有数据路径，表现就是「都显示在线，但互相连不上」。
     */
    public static String recommendedNode() {
        for (String n : PUBLIC_NODES) {
            if (probe(n)) return n;
        }
        return null;
    }

    /** 当前进程是否具备管理员权限 */
    public static boolean isElevated() {
        try {
            Process p = new ProcessBuilder("net", "session").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 启动前预检。覆盖小白最常撞到的两类问题：
     * 没有管理员权限、安全软件把虚拟网卡驱动隔离了。
     */
    public static Preflight preflight(File engineDir, boolean admin) {
        if (engineDir == null || !engineDir.isDirectory()) {
            return new Preflight(false, "缺少内核文件",
                    "未找到内核目录 engine。",
                    "请确认已完整解压压缩包，且 app 文件夹存在。");
        }
        File core = new File(engineDir, "easytier-core.exe");
        if (!core.isFile()) {
            return new Preflight(false, "缺少内核文件",
                    "未找到 easytier-core.exe。",
                    "请重新解压压缩包，或从同版本的其他电脑复制 engine 文件夹。");
        }
        File wintun = new File(engineDir, "wintun.dll");
        if (!wintun.isFile()) {
            return new Preflight(false, "缺少虚拟网卡驱动",
                    "未找到 wintun.dll，该文件用于创建虚拟网卡。",
                    "请重新解压压缩包；若文件已存在，说明被安全软件隔离，请将其加入白名单。");
        }
        if (!admin) {
            return new Preflight(false, "需要管理员权限",
                    "创建虚拟网卡必须加载系统驱动，普通权限下无法完成。",
                    "请右键单击本程序，选择「以管理员身份运行」，并在系统提示中选择「允许」。");
        }
        return new Preflight(true, "检查通过", "", "");
    }

    /**
     * 在指定区间内选择一个空闲端口。
     * 房主监听端口每次建房都变，避免公网上出现固定的 P2P 端口被扫描器识别。
     */
    public static int pickPort(int from, int to) {
        Random r = new Random();
        int span = Math.max(1, to - from);
        int start = from + r.nextInt(span);
        for (int i = 0; i < span; i++) {
            int p = from + ((start - from + i) % span);
            if (portFree(p)) return p;
        }
        return start;
    }

    public static boolean portFree(int port) {
        try (java.net.ServerSocket s = new java.net.ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 把底层异常翻译成小白能懂的一句话。
     * 只保留四类结论：权限、拦截、端口占用、文件缺失。
     */
    public static String humanError(String raw) {
        String s = raw == null ? "" : raw;
        String low = s.toLowerCase();
        if (low.contains("wintun")) {
            return "虚拟网卡驱动被安全软件拦截或已缺失，请将程序文件夹加入白名单后重试。";
        }
        if (s.contains("10048") || s.contains("地址正在使用") || s.contains("端口被占用")) {
            return "监听端口被其他程序占用，请断开连接后重试。";
        }
        if (s.contains("权限") || low.contains("access is denied") || low.contains("access denied")) {
            return "创建虚拟网卡需要管理员权限，请以管理员身份运行本程序。";
        }
        if (s.contains("内核") || s.contains("easytier-core") || s.contains("找不到")) {
            return "内核文件缺失，请重新解压程序压缩包。";
        }
        return s.isEmpty() ? "未知错误，请查看运行日志。" : s;
    }

    /**
     * 为本程序与内核添加防火墙入站允许规则。
     * 未放行是"首次弹窗点了取消后永远连不上"的头号原因。
     */
    public static boolean addFirewallRules(File appExe, File coreExe) {
        boolean all = true;
        boolean any = false;
        if (appExe != null && appExe.isFile()) {
            any = true;
            all &= runNetsh("add", appExe.getAbsolutePath(), "app");
        }
        if (coreExe != null && coreExe.isFile()) {
            any = true;
            all &= runNetsh("add", coreExe.getAbsolutePath(), "core");
        }
        return all && any;
    }

    public static void removeFirewallRules(File appExe, File coreExe) {
        if (appExe != null && appExe.isFile()) runNetsh("delete", appExe.getAbsolutePath(), "app");
        if (coreExe != null && coreExe.isFile()) runNetsh("delete", coreExe.getAbsolutePath(), "core");
    }

    /** 规则名保持纯 ASCII，避免 netsh 在不同代码页下的解析问题 */
    private static boolean runNetsh(String action, String programPath, String tag) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("netsh");
            cmd.add("advfirewall");
            cmd.add("firewall");
            cmd.add(action.equals("add") ? "add" : "delete");
            cmd.add("rule");
            cmd.add("name=LANLink (" + tag + ")");
            cmd.add("dir=in");
            if (action.equals("add")) {
                cmd.add("action=allow");
                cmd.add("enable=yes");
            }
            cmd.add("program=\"" + programPath + "\"");
            Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            proc.getInputStream().readAllBytes();
            return proc.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
