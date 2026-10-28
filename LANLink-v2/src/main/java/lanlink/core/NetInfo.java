package lanlink.core;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 本机网络信息：挑出用户真正在用的那块网卡 */
public final class NetInfo {

    public static final class Iface {
        public final String name;
        public final String ip;
        public final boolean virtual;
        final int score;
        Iface(String n, String i, boolean v, int s) { name = n; ip = i; virtual = v; score = s; }
        @Override public String toString() { return name + "  " + ip; }
    }

    private static final String VIRTUAL = "(?i).*(vmware|virtualbox|vbox|hyper-v|vethernet|radmin|hamachi|tap-|tun\\d|tailscale|zerotier|docker|wsl|npcap|loopback).*";
    private static final String PHYSICAL = "(?i)^(wlan|wi-?fi|ethernet|eth\\d|en\\d|wlp|eno|enp).*";

    private NetInfo() {}

    public static List<Iface> ipv4() {
        List<Iface> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getDisplayName() == null ? ni.getName() : ni.getDisplayName();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip.startsWith("10.144.0.")) continue;      // 自己的虚拟网卡，忽略
                    boolean virt = name.matches(VIRTUAL);
                    int score = 0;
                    if (name.matches(PHYSICAL)) score -= 2;
                    if (virt) score += 4;
                    if (ip.endsWith(".1")) score += 2;
                    out.add(new Iface(name, ip, virt, score));
                }
            }
        } catch (Exception ignored) { }
        out.sort((x, y) -> Integer.compare(x.score, y.score));
        return out;
    }

    public static String bestLanIp() {
        List<Iface> l = ipv4();
        for (Iface i : l) {
            if (i.ip.startsWith("192.168.") || i.ip.startsWith("172.") || i.ip.startsWith("10.")) return i.ip;
        }
        return l.isEmpty() ? "127.0.0.1" : l.get(0).ip;
    }

    public static String hostName() {
        try {
            String n = InetAddress.getLocalHost().getHostName();
            return n == null || n.isEmpty() ? "PC" : n;
        } catch (Exception e) {
            return "PC";
        }
    }

    /**
     * 本机指纹：用于派生稳定的虚拟 IP。
     * 取物理网卡的 MAC 地址与计算机名，MAC 优先取非虚拟网卡，避免虚拟机/虚拟网卡切换导致地址漂移。
     */
    public static String fingerprint() {
        String mac = "";
        try {
            List<NetworkInterface> nis = new ArrayList<>(
                    Collections.list(NetworkInterface.getNetworkInterfaces()));
            nis.sort((a, b) -> {
                String na = a.getDisplayName() == null ? a.getName() : a.getDisplayName();
                String nb = b.getDisplayName() == null ? b.getName() : b.getDisplayName();
                boolean va = na.matches(VIRTUAL);
                boolean vb = nb.matches(VIRTUAL);
                if (va != vb) return va ? 1 : -1;      // 物理网卡优先
                return na.compareToIgnoreCase(nb);
            });
            for (NetworkInterface ni : nis) {
                byte[] h = ni.getHardwareAddress();
                if (h != null && h.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (byte b : h) sb.append(String.format("%02x", b));
                    mac = sb.toString();
                    break;
                }
            }
        } catch (Exception ignored) { }
        if (mac.isEmpty()) mac = "no-mac";
        return mac + "|" + hostName();
    }

    /** 虚拟网卡是否已经建好并拿到 10.144.0.x 地址 */
    public static boolean virtualNicUp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.getHostAddress().startsWith("10.144.0.")) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }
}
