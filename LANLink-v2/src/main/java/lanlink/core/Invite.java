package lanlink.core;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 邀请码：把「对方 IP + 端口 + 房间密钥」压缩成一行人能读、能念、能微信发的东西。
 *
 * 格式：  IP:端口#密钥      例： 113.133.208.7:41273#k7m2q9x3rt5vb6n
 *
 * 密钥同时充当内核的 network_secret（房间身份），长度 16 位、31 字符集，
 * 强度约 7.2×10^23，无法暴力破解。
 *
 * 网络名称固定为常量 NETWORK_NAME：实测表明，同名网络、不同密钥的节点
 * 互相无法发现，鉴权完全由密钥承担，因此名称无须承载任何密码信息
 * （早期版本用名称承载密码哈希，名称一旦泄露即等于密码可离线爆破）。
 */
public final class Invite {

    /** 内核 network_name，固定值 */
    public static final String NETWORK_NAME = "lanlink";
    /** 密钥最短长度 */
    public static final int SECRET_MIN = 12;
    /** 密钥最长长度 */
    public static final int SECRET_MAX = 64;
    /** 生成的密钥长度 */
    public static final int DEFAULT_SECRET_LENGTH = 16;
    /** 旧版固定端口，仅用于兼容解析 */
    public static final int LEGACY_PORT = 11010;
    /** 加密字符集，去除易混字符 i l o 0 1 */
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    /** 虚拟局域网网段前缀 */
    public static final String VNET_PREFIX = "10.144.0.";

    /** 从任意聊天文本里抠出 <地址>#<密钥>；地址必须以字母数字或 [ 开头，避免误吞冒号 */
    private static final Pattern CODE = Pattern.compile(
            "([0-9a-zA-Z\\[]+[0-9a-zA-Z\\.\\-:\\[\\]]*)#([^\\s，。,;；]+)");

    public final String host;
    public final int port;
    public final String secret;

    private Invite(String host, int port, String secret) {
        this.host = host;
        this.port = port;
        this.secret = secret;
    }

    /** 内核 network_name，固定常量 */
    public String roomName() {
        return NETWORK_NAME;
    }

    /**
     * 成员虚拟 IP：由密钥与本机指纹派生。
     * 同一台机器在同一个房间内永远得到同一个地址（重连不变，用户记住的地址一直有效）；
     * 不同机器几乎必然不同。
     */
    public static String deriveVirtualIp(String secret, String fingerprint) {
        int h = hashInt("lanlink-ip|" + secret + "|" + (fingerprint == null ? "" : fingerprint));
        return VNET_PREFIX + (2 + Math.floorMod(h, 250));
    }

    /** 冲突自愈：地址 +1，超出范围则回到 2 */
    public static String nextVirtualIp(String ip) {
        try {
            int last = Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1));
            last = (last >= 254) ? 2 : last + 1;
            return VNET_PREFIX + last;
        } catch (Exception e) {
            return VNET_PREFIX + "2";
        }
    }

    public static String randomSecret() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(DEFAULT_SECRET_LENGTH);
        for (int i = 0; i < DEFAULT_SECRET_LENGTH; i++) {
            sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public String format() {
        return host + ":" + port + "#" + secret;
    }

    public String displayHost() { return host + ":" + port; }

    /**
     * 扫描器/微信常把全角字符一并带过来（：＃－ 等），先做一次等价替换。
     * 只处理「确定等价」的几种：全角冒号/井号/连字符/减号/句点、不间断空格。
     * 不做更激进的清洗 —— 宁可报错，也不要把用户的真实地址改坏。
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        return raw.replace("：", ":")
                .replace("＃", "#")
                .replace("－", "-")
                .replace("—", "-")
                .replace("﹣", "-")
                .replace("．", ".")
                .replace("\u3000", " ")
                .replace("\u00A0", " ")
                .trim();
    }

    public static Invite parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("请输入邀请码");
        String s = normalize(raw);
        if (s.isEmpty()) throw new IllegalArgumentException("请输入邀请码");

        String hostPart;
        String secret = null;

        // 网友常把"邀请码：xxx 你看下"整段粘进来，先把代码段抠出来
        Matcher m = CODE.matcher(s);
        if (m.find()) {
            hostPart = m.group(1);
            secret = m.group(2);
        } else {
            int hash = s.indexOf('#');
            if (hash >= 0) {
                hostPart = s.substring(0, hash);
                secret = s.substring(hash + 1);
            } else {
                hostPart = s;
            }
        }

        hostPart = hostPart.trim().replaceAll("^[a-zA-Z]+://", "");
        secret = secret == null ? null : secret.trim();
        if (hostPart.isEmpty()) throw new IllegalArgumentException("缺少对方 IP 地址");

        int port = LEGACY_PORT;
        String host;
        if (hostPart.startsWith("[")) {                          // [IPv6]:port
            int end = hostPart.indexOf(']');
            if (end < 0) throw new IllegalArgumentException("IPv6 地址格式错误");
            host = hostPart.substring(1, end);
            String rest = hostPart.substring(end + 1).trim();
            if (rest.startsWith(":") && rest.length() > 1) port = parsePort(rest.substring(1));
        } else {
            int c = hostPart.lastIndexOf(':');
            boolean singleColon = c > 0 && hostPart.indexOf(':') == c;
            if (singleColon && hostPart.substring(c + 1).matches("\\d{1,5}")) {
                port = parsePort(hostPart.substring(c + 1));
                host = hostPart.substring(0, c);
            } else {
                host = hostPart;                                  // 纯 v4 或裸 IPv6
            }
        }

        host = host.trim();
        if (host.startsWith(":") || !isIp(host)) {
            throw new IllegalArgumentException("IP 地址格式不正确：" + host);
        }
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("缺少房间密钥（房主界面上有密钥）");
        }
        if (secret.length() < SECRET_MIN) {
            throw new IllegalArgumentException("房间密钥长度不足，请重新复制房主提供的邀请码");
        }
        if (secret.length() > SECRET_MAX) {
            throw new IllegalArgumentException("房间密钥长度超出范围");
        }

        return new Invite(host.toLowerCase(Locale.ROOT), port, secret);
    }

    /** 只填了 IP、密钥单独填的输入方式 */
    public static Invite of(String hostText, String secret) {
        String h = hostText == null ? "" : hostText.trim();
        String s = secret == null ? "" : secret.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("请填写房间密钥");
        return parse(h + "#" + s);
    }

    /** 只接受真正的 IP 字面量，不接受域名 / 半截地址 */
    static boolean isIp(String s) {
        if (s == null || s.isEmpty()) return false;
        if (isIpv4(s)) return true;
        if (s.indexOf(':') < 0) return false;
        if (!s.matches("[0-9a-fA-F:\\.]+")) return false;
        try {
            return InetAddress.getByName(s) instanceof Inet6Address;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isIpv4(String s) {
        String[] p = s.split("\\.", -1);
        if (p.length != 4) return false;
        for (String x : p) {
            if (!x.matches("\\d{1,3}")) return false;
            int n = Integer.parseInt(x);
            if (n < 0 || n > 255) return false;
        }
        return true;
    }

    private static int parsePort(String s) {
        try {
            int p = Integer.parseInt(s.trim());
            if (p <= 0 || p > 65535) throw new IllegalArgumentException("端口超出范围");
            return p;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("端口不是数字：" + s);
        }
    }

    static String hash8(String s) {
        return String.format("%08x", hashInt(s));
    }

    static int hashInt(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return ((d[0] & 0xFF) << 24) | ((d[1] & 0xFF) << 16)
                    | ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
        } catch (Exception e) {
            return s.hashCode();
        }
    }
}
