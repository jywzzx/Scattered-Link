package lanlink.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 手机端配置的「唯一事实来源」。
 *
 * 为什么单独抽出来：
 * 1) 此前手机端 TOML 只在建房时生成了「直连模式」的版本。用户勾选「强制统一中继」后，
 *    界面上的提示已经变了，但已经发给手机的配置还是旧的 —— 这就是
 *    「手机端配置不随模式变化」。由于手机端 TOML 与电脑端内核参数分别由
 *    两段代码生成，两边一旦不同步，症状是「两端都显示在线、但互相连不上」。
 * 2) 用户导入 TOML 时不会打开文件读注释。所以写在 TOML 里的提示（尤其
 *    「第二台手机请把 ipv4 改成 10.144.0.10」）等于没写。真正的提示必须由
 *    电脑端界面直接显示、由用户当面转达。
 *
 * 因此：本类同时产出
 *   · TOML 正文（只含内核真正认的键，不留给人读的说明）
 *   · 界面提示条目（哪项必须核对、哪项要改号码），供 UI 直接展示
 */
public final class PhoneProfile {

    /** 一条给用户看的核对项。level: info / warn / error */
    public static final class Tip {
        public final String level;
        public final String text;

        Tip(String level, String text) {
            this.level = level;
            this.text = text;
        }
    }

    /** 与电脑端内核完全一致的运行模式。手机端配置必须跟着它走，不能各写一套。 */
    public enum Mode {
        /** 直连优先：局域网直连 + 公网直连 + 公共节点中继兜底 */
        DIRECT("直连优先"),
        /** 强制统一中继：禁用 P2P 与打洞，只连指定节点 */
        RELAY("强制统一中继");

        public final String label;

        Mode(String label) { this.label = label; }
    }

    public final Mode mode;
    public final Invite invite;
    /** 公网/对端地址（不含端口） */
    public final String hostIp;
    public final String secret;
    /** 房主局域网地址，可为 null */
    public final String lanIp;
    public final int listenPort;
    /** 分配给这台手机的虚拟 IP */
    public final String phoneIp;
    /** RELAY 模式下的唯一节点；DIRECT 模式下忽略 */
    public final String relayNode;
    /** DIRECT 模式下作为兜底的公共节点；RELAY 模式下忽略 */
    public final List<String> publicNodes;

    private PhoneProfile(Mode mode, Invite invite, String hostIp, String secret, String lanIp,
                         int listenPort, String phoneIp, String relayNode, List<String> publicNodes) {
        this.mode = mode;
        this.invite = invite;
        this.hostIp = hostIp;
        this.secret = secret;
        this.lanIp = lanIp;
        this.listenPort = listenPort;
        this.phoneIp = phoneIp;
        this.relayNode = relayNode;
        this.publicNodes = publicNodes == null ? new ArrayList<>() : new ArrayList<>(publicNodes);
    }

    public static PhoneProfile direct(Invite invite, String hostIp, String secret, String lanIp,
                                      int listenPort, String phoneIp, List<String> publicNodes) {
        return new PhoneProfile(Mode.DIRECT, invite, hostIp, secret, lanIp, listenPort, phoneIp,
                null, publicNodes);
    }

    public static PhoneProfile relay(Invite invite, String hostIp, String secret, String lanIp,
                                     int listenPort, String phoneIp, String relayNode) {
        return new PhoneProfile(Mode.RELAY, invite, hostIp, secret, lanIp, listenPort, phoneIp,
                relayNode, null);
    }

    /** 第二台手机该用的地址 */
    public String secondPhoneIp() {
        return Invite.nextVirtualIp(phoneIp);
    }

    /** 该模式下实际会写入 TOML 的节点列表（便于界面显示"本模式连哪几条路"） */
    public List<String> effectivePeers() {
        List<String> out = new ArrayList<>();
        if (mode == Mode.RELAY) {
            if (relayNode != null && !relayNode.trim().isEmpty()) out.add(relayNode.trim());
            return out;
        }
        if (lanIp != null && !lanIp.isEmpty() && !lanIp.equals(hostIp)) {
            out.add("tcp://" + lanIp + ":" + listenPort);
            out.add("udp://" + lanIp + ":" + listenPort);
        }
        out.add("tcp://" + hostIp + ":" + listenPort);
        out.add("udp://" + hostIp + ":" + listenPort);
        out.addAll(publicNodes);
        return out;
    }

    /**
     * 生成内核可直接读取的 TOML。
     *
     * 刻意不写任何给人看的注释：用户拿到文件是直接导入的，不会打开看，
     * 注释只会让文件变长、导入框里满屏井号。所有提示改由 {@link #tips()} 交给界面。
     */
    public String toToml() {
        StringBuilder sb = new StringBuilder();
        sb.append("ipv4 = \"").append(phoneIp).append("/24\"\n");
        if (mode == Mode.RELAY) {
            // 三项与命令行 --disable-p2p 等一一对应，已用 v2.6 内核实测可用
            sb.append("latency_first = true\n");
            sb.append("disable_p2p = true\n");
            sb.append("disable_udp_hole_punching = true\n");
            sb.append("disable_tcp_hole_punching = true\n");
        }
        sb.append("\n[network_identity]\n");
        sb.append("network_name = \"").append(invite.roomName()).append("\"\n");
        sb.append("network_secret = \"").append(secret).append("\"\n");

        for (String uri : effectivePeers()) {
            sb.append("\n[[peer]]\nuri = \"").append(uri).append("\"\n");
        }
        return sb.toString();
    }

    /**
     * 界面要显示的核对项 —— 这才是用户真正会读到的地方。
     *
     * 导入后手机端必须满足的条件，逐条列出；不合规的那条用 warn/error 标出来，
     * 让「DHCP 没取消」「第二台手机没改号」这两个高频事故在界面上就被拦住。
     */
    public List<Tip> tips() {
        List<Tip> out = new ArrayList<>();

        // 第一条是「怎么把这段东西搬到手机上」。扫码之后用户手里只有一段文本，
        // 而手机端只有「导入配置文件」这一条路 —— 必须把「文本 → 文件」这一步写清楚，
        // 否则扫完码的用户会卡在「然后呢」。
        out.add(new Tip("info", "手机端只有「导入配置文件」，所以要先把配置变成文件："
                + "扫码后全选复制 → 手机记事本粘贴 → 存为 lanlink.toml → 在 EasyTier 里导入它"));
        out.add(new Tip("info", "导入后先取消勾选「DHCP」，再把虚拟 IPv4 改成 " + phoneIp + "/24"));
        out.add(new Tip("warn", "第二台及以后的手机，必须把虚拟 IPv4 依次改为 "
                + secondPhoneIp() + "/24" + "、再下一台继续 +1；同号会互相抢占、彼此不通"
                + "（每台手机拿到的是同一份配置，所以这个号必须有人工改，程序无法代劳）"));

        if (mode == Mode.RELAY) {
            out.add(new Tip("info", "本房间为「强制统一中继」：所有手机都已禁用直连与打洞，只连下面这一个节点"));
            out.add(new Tip("error", "每台手机的初始节点必须是同一个完整地址："
                    + (relayNode == null || relayNode.isEmpty() ? "（尚未取到可用节点）" : relayNode)));
            out.add(new Tip("warn", "该模式下请勿再让手机改用其他公共节点；改动任一台即可导致全网互不可见"));
        } else {
            out.add(new Tip("info", "本房间为「直连优先」：手机将同时尝试局域网直连、公网直连与公共节点中继"));
            if (lanIp != null && !lanIp.isEmpty() && !lanIp.equals(hostIp)) {
                out.add(new Tip("info", "同一 WiFi 下会优先走内网 " + lanIp + ":" + listenPort + "，延迟最低"));
            } else {
                out.add(new Tip("warn", "没拿到可用的局域网地址：同一 WiFi 下也会绕公网，可能连不上"));
            }
            if (publicNodes.isEmpty()) {
                out.add(new Tip("warn", "当前未启用公共节点：一旦双方都在运营商大内网，将没有中继兜底"));
            }
        }
        out.add(new Tip("info", "手机端「端口转发」必须留空；填入地址会因该地址不属于本机网卡而报 os error 99"));
        out.add(new Tip("info", ipCollisionHint()));
        return out;
    }

    /**
     * 分配的手机 IPv4 是否可能与房间内其他设备冲突。
     *
     * 之前这里从 .9 起分配，而电脑端自己的虚拟地址可能是 .1，成员加入方由密钥派生
     * 得到 .2~.251 中任意一个 —— 也就是说 .9 完全可能正是某位已经在线玩家的地址。
     * 更隐蔽的是：同一份配置发给两台手机，两台都会用 .9，它们谁也连不上谁。
     * 这里不假装能算出唯一值（手机的真实地址无法预知），只把冲突显式说出来。
     */
    public String ipCollisionHint() {
        return "手机分配到的 " + phoneIp + " 与房间内其他设备的地址无冲突保证：若该号已被占用，"
                + "手机将显示「IP 冲突」或始终看到 0 个成员，此时把虚拟 IPv4 改成 "
                + Invite.nextVirtualIp(Invite.nextVirtualIp(phoneIp)) + " 再试";
    }

    /** 一行式模式说明，给状态区用 */
    public String modeSummary() {
        return mode == Mode.RELAY
                ? "强制统一中继 · 只连 " + (relayNode == null || relayNode.isEmpty() ? "（未取到节点）" : relayNode)
                : "直连优先 · 内网/公网直连 + 公共节点兜底";
    }

    /**
     * 手机配置的「模式」与电脑端内核**实际运行的**模式是否已分叉。
     *
     * 这是「勾了强制中继、手机也按中继导入、却互相看不到」的根因所在：
     * 内核只在点「创建房间」那一刻读一次复选框。之后用户再勾选强制中继，
     * 界面文案、二维码、TOML 全部跟着变成中继模式，但已经跑起来的电脑端内核
     * 仍是直连模式（仍然挂着 3 个公共节点、没开 disable_*）。
     *
     * 结果是：手机只连 225284.xyz 且禁用打洞，电脑端挂在别的公共节点上，
     * 两者没有任何共同通道 —— 手机日志里能看到它连上了中继，
     * 但成员列表永远只有它自己。
     *
     * @return 需要立刻告知用户的不一致说明；一致时返回 null
     */
    public String divergenceHint(boolean runningNoP2p, boolean runningUsePublicNodes,
                                 String runningRelayUri) {
        boolean kernelRelay = runningNoP2p;
        if (mode == Mode.RELAY && !kernelRelay) {
            return "【两端模式不一致】电脑端内核仍在按「直连优先」运行（本次启动时未勾选强制中继），"
                    + "但手机配置已是「强制统一中继」。这样手机与电脑不会共用同一个中继通道，"
                    + "表现就是手机显示已连上节点、成员列表却只有它自己。"
                    + "请勾选状态保持不变，回到首页重新「创建房间」以重启内核。";
        }
        if (mode != Mode.RELAY && kernelRelay) {
            return "【两端模式不一致】电脑端内核正在「强制统一中继」，但手机配置是「直连优先」。"
                    + "请重新建房或重新生成手机配置。";
        }
        if (mode == Mode.RELAY && kernelRelay) {
            String kr = runningRelayUri == null ? "" : runningRelayUri.trim();
            String mine = relayNode == null ? "" : relayNode.trim();
            if (!kr.isEmpty() && !mine.isEmpty() && !kr.equals(mine)) {
                return "【中继节点不一致】电脑端内核连的是 " + kr + "，手机配置写的是 "
                        + mine + "。双方必须连同一个节点，否则彼此不可见。";
            }
            if (kr.isEmpty()) {
                return "【电脑端未挂中继节点】电脑端内核没有可用的中继地址，手机连上节点也找不到它。";
            }
        }
        if (mode == Mode.RELAY && runningUsePublicNodes) {
            return "【电脑端仍在挂公共节点】强制中继模式下电脑端应只连一个节点，"
                    + "当前它还挂着其他公共节点，可能让手机与电脑落到不同通道。";
        }
        return null;
    }
}
