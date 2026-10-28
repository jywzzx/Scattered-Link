package lanlink.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 手机端配置的「可搬运形态」：二维码内容 + 微信可发的文本 + 可回读解析。
 *
 * 为什么要额外包一层，而不是直接把 TOML 编码进二维码：
 *
 * 1) 手机扫到的是一段纯文本，用户必须把它变成手机上的一个 .toml 文件才能导入。
 *    裸 TOML 里没有任何「这是什么、该怎么用」的信息，用户扫完只看到
 *    `ipv4 = "10.144.0.9/24"` 这种看不懂的内容，极大概率直接放弃 ——
 *    实测反馈就是「扫了不知道干什么」。
 * 2) 微信 / QQ 会对纯文本消息自动加空白、自动折行、偶尔把 `//` 当链接处理。
 *    裸 TOML 一旦被改动，导入就是解析失败，而报错信息是英文的，用户无法自助。
 *    所以文本里必须带一段「不用改也能用」的说明，并且解析时要能容忍这些变形。
 * 3) 电脑端的「加入房间」也要能吃这段文本 —— 用户可能只是想把配置倒回另一台电脑。
 *
 * 格式刻意保持**人能读**：分隔线用等号，字段用「键：值」，不引入任何二进制/编码。
 *
 *    ───── LANLink 配置 v1 ─────
 *    mode: direct
 *    ipv4: 10.144.0.9
 *    secret: xxxx
 *    （说明行…）
 *    ─── 配置开始 ───
 *    ipv4 = "10.144.0.9/24"
 *    ...
 *    ─── 配置结束 ───
 */
public final class PhoneConfigText {

    public static final String HEAD = "───── Sparsa 零星 配置 v1 ─────";
    public static final String TOML_BEGIN = "─── 配置开始 ───";
    public static final String TOML_END = "─── 配置结束 ───";

    private PhoneConfigText() { }

    /**
     * 生成给人搬运的完整文本。
     *
     * 说明行的措辞按用户实际会做的事来写：手机上就是「新建 txt → 改后缀 → 导入」，
     * 所以直接写这三步，不写「请使用 TOML 配置」这种没有操作性的句子。
     */
    public static String encode(PhoneProfile p) {
        String toml = p.toToml();
        StringBuilder sb = new StringBuilder();
        sb.append(HEAD).append('\n');
        sb.append("mode: ").append(p.mode == PhoneProfile.Mode.RELAY ? "relay" : "direct").append('\n');
        sb.append("ipv4: ").append(p.phoneIp).append('\n');
        sb.append("secret: ").append(p.secret).append('\n');
        sb.append('\n');
        sb.append("【手机】用记事本新建文件，把下面两行标记之间的内容全部粘贴进去，\n");
        sb.append("保存为 sparsa.toml，再在 EasyTier 里「导入配置文件」选择它。\n");
        sb.append("【第二台手机】导入后先取消勾选 DHCP，再把虚拟 IPv4 改成 ")
                .append(p.secondPhoneIp()).append("/24。\n");
        if (p.mode == PhoneProfile.Mode.RELAY) {
            sb.append("【本房间】强制统一中继：每台手机的初始节点必须都是 ")
                    .append(p.relayNode == null || p.relayNode.isEmpty() ? "（未取到）" : p.relayNode)
                    .append("，\n不要换成别的节点，否则彼此不可见。\n");
        }
        sb.append('\n');
        sb.append(TOML_BEGIN).append('\n');
        sb.append(toml);
        if (!toml.endsWith("\n")) sb.append('\n');
        sb.append(TOML_END).append('\n');
        return sb.toString();
    }

    /** 是否是我们生成的这种文本（用来和裸 TOML 区分，决定解析策略） */
    public static boolean looksLikeOurs(String s) {
        return s != null && s.contains(TOML_BEGIN);
    }

    /**
     * 从任意粘贴文本里取出 TOML 正文。
     *
     * 兼容三种来源：
     *   1) 二维码/微信文本 —— 取两个标记之间；
     *   2) 用户在手机上手写、只有 TOML —— 原样返回；
     *   3) 微信把标记行吃掉了/换成了别的横线 —— 退化为「找第一个像配置的行」。
     *
     * 返回 null 表示这段文本里没有可用的配置。
     */
    public static String extractToml(String raw) {
        if (raw == null) return null;
        String s = raw.replace("\r\n", "\n").replace("\r", "\n");
        int b = s.indexOf(TOML_BEGIN);
        int e = s.indexOf(TOML_END);
        if (b >= 0 && e > b) {
            return s.substring(b + TOML_BEGIN.length(), e).trim() + "\n";
        }
        // 标记被改坏：从第一个形如 key = value 或 [section] 的行开始，到文本末尾
        String[] lines = s.split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.startsWith("ipv4") || t.startsWith("[network_identity]")) { start = i; break; }
        }
        if (start < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            String t = lines[i];
            if (t.contains(TOML_END)) break;
            sb.append(t).append('\n');
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out + "\n";
    }

    /** 解析结果：解出来的字段 + 解析过程中发现的问题（供界面直接展示） */
    public static final class Parsed {
        public final String toml;
        /** 虚拟 IPv4（不含 /24），可能为 null */
        public final String ipv4;
        /** 是否强制中继模式 */
        public final boolean relay;
        /** network_secret，即邀请码里的房间密钥，可能为 null */
        public final String secret;
        /** 第一个 peer 的 uri，可能为 null */
        public final String firstPeer;
        /** 问题列表（空 = 完全识别） */
        public final List<String> problems;
        /** 该配置能还原出的邀请码（需要 peer 与 secret），可能为 null */
        public final Invite invite;

        Parsed(String toml, String ipv4, boolean relay, String secret, String firstPeer,
               List<String> problems, Invite invite) {
            this.toml = toml;
            this.ipv4 = ipv4;
            this.relay = relay;
            this.secret = secret;
            this.firstPeer = firstPeer;
            this.problems = problems;
            this.invite = invite;
        }
    }

    /**
     * 解析一段粘贴进来的手机配置。永远返回非 null；无法识别时 toml 为 null。
     */
    public static Parsed parse(String raw) {
        String toml = extractToml(raw);
        List<String> problems = new ArrayList<>();
        if (toml == null) {
            problems.add("没有找到配置内容：需要包含 ipv4 与 [network_identity] 两段");
            return new Parsed(null, null, false, null, null, problems, null);
        }

        String ipv4 = null;
        String secret = null;
        String firstPeer = null;
        boolean relay = false;

        for (String line : toml.split("\n")) {
            String t = line.trim();
            if (t.startsWith("#") || t.isEmpty()) continue;      // 用户可能自己加了注释
            String k = keyOf(t);
            String v = valueOf(t);
            if ("ipv4".equals(k)) {
                ipv4 = v;
                int slash = ipv4.indexOf('/');
                if (slash > 0) ipv4 = ipv4.substring(0, slash);
            } else if ("network_secret".equals(k)) {
                secret = v;
            } else if ("uri".equals(k)) {
                if (firstPeer == null) firstPeer = v;
            } else if ("disable_p2p".equals(k)) {
                relay = "true".equalsIgnoreCase(v);
            }
        }

        if (ipv4 != null && !Invite.isIpv4(ipv4)) {
            problems.add("虚拟 IPv4 不是合法地址：" + ipv4);
        }
        if (secret == null || secret.isEmpty()) {
            problems.add("缺少 network_secret：该配置不能加入任何房间");
        }
        if (firstPeer == null) {
            problems.add("缺少 [[peer]]：手机不知道连谁，会一直转圈");
        }

        // 只有「对方地址 + 房间密钥」都像样时才还原邀请码，否则宁可不给
        Invite inv = null;
        String host = hostOf(firstPeer);
        if (host != null && secret != null && !secret.isEmpty()) {
            try {
                inv = Invite.parse(host + "#" + secret);
            } catch (RuntimeException ignored) {
                // 端口非法等情况：不进 problems，因为这段配置对手机仍可能可用
            }
        }
        return new Parsed(toml, ipv4, relay, secret, firstPeer, problems, inv);
    }

    /** `uri = "tcp://1.2.3.4:41273"` → `1.2.3.4:41273`，并剔除公共节点 */
    private static String hostOf(String uri) {
        if (uri == null) return null;
        String u = Invite.normalize(uri);
        int p = u.indexOf("://");
        if (p >= 0) u = u.substring(p + 3);
        int q = u.indexOf('/');
        if (q > 0) u = u.substring(0, q);
        if (u.isEmpty()) return null;
        String lower = u.toLowerCase(java.util.Locale.ROOT);
        // 公共节点不是「对方」，用它还原邀请码会得到一个指向公共节点的假邀请码
        for (String n : Diagnostics.PUBLIC_NODES) {
            if (lower.equals(stripScheme(n).toLowerCase(java.util.Locale.ROOT))) return null;
        }
        return u.contains(":") ? u : u + ":" + Invite.LEGACY_PORT;
    }

    private static String stripScheme(String uri) {
        int p = uri.indexOf("://");
        return p >= 0 ? uri.substring(p + 3) : uri;
    }

    private static String keyOf(String line) {
        int eq = line.indexOf('=');
        return eq < 0 ? "" : line.substring(0, eq).trim();
    }

    private static String valueOf(String line) {
        int eq = line.indexOf('=');
        if (eq < 0) return "";
        String v = line.substring(eq + 1).trim();
        if (v.endsWith(",")) v = v.substring(0, v.length() - 1).trim();
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"")
                || v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length() - 1);
        }
        return v.trim();
    }
}
