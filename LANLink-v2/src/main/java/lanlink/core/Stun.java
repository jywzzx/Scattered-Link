package lanlink.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 STUN 客户端（RFC 5389 Binding Request）。
 * 唯一用途：告诉房主"你的公网 IP 是什么"，好让朋友能填。
 * 纯 JDK 实现，不依赖任何第三方库。
 */
public final class Stun {

    private static final int MAGIC = 0x2112A442;
    private static final String[] SERVERS = {"stun.miwifi.com", "stun.hitv.com", "stun.cloudflare.com"};
    private static final int PORT = 3478;

    public static final class Mapped {
        public final String address;
        public final int port;
        public Mapped(String a, int p) { address = a; port = p; }
        @Override public String toString() { return address + ":" + port; }
    }

    private Stun() {}

    /** 依次尝试多个服务器，返回第一个成功结果；全部失败返回 null */
    public static Mapped query() {
        for (String host : SERVERS) {
            Mapped m = queryOne(host, 1200);
            if (m != null) return m;
        }
        return null;
    }

    /** 两次查询对比端口，用于粗略判断（同一 socket 才会一致） */
    public static Map<String, Mapped> probeAll() {
        Map<String, Mapped> out = new LinkedHashMap<>();
        for (String host : SERVERS) {
            Mapped m = queryOne(host, 1200);
            if (m != null) out.put(host, m);
        }
        return out;
    }

    private static Mapped queryOne(String host, int timeoutMs) {
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(timeoutMs);
            byte[] txid = new byte[12];
            new java.security.SecureRandom().nextBytes(txid);

            ByteBuffer req = ByteBuffer.allocate(20);
            req.putShort((short) 0x0001);
            req.putShort((short) 0);
            req.putInt(MAGIC);
            req.put(txid);

            InetAddress addr = InetAddress.getByName(host);
            sock.send(new DatagramPacket(req.array(), 20, addr, PORT));

            byte[] buf = new byte[1024];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            sock.receive(resp);
            return parse(buf, resp.getLength(), txid);
        } catch (SocketTimeoutException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static Mapped parse(byte[] buf, int len, byte[] txid) {
        if (len < 20) return null;
        ByteBuffer b = ByteBuffer.wrap(buf, 0, len);
        int type = b.getShort() & 0xffff;
        if ((type & 0xFF00) != 0x0100) return null;      // 只认 success response
        b.getShort();                                     // length
        if (b.getInt() != MAGIC) return null;
        byte[] gotTx = new byte[12];
        b.get(gotTx);
        if (!java.util.Arrays.equals(gotTx, txid)) return null;

        int attrLen = 0;
        while (b.remaining() >= 4) {
            int t = b.getShort() & 0xffff;
            int l = b.getShort() & 0xffff;
            if (b.remaining() < l) break;
            int start = b.position();
            if ((t == 0x0020 || t == 0x0001) && l >= 8) {
                boolean xor = (t == 0x0020);
                int family = b.get(start + 1) & 0xff;
                int port = ((buf[start + 2] & 0xff) << 8) | (buf[start + 3] & 0xff);
                if (xor) port ^= (MAGIC >>> 16);
                if (family == 0x01) {
                    int ip = ((buf[start + 4] & 0xff) << 24) | ((buf[start + 5] & 0xff) << 16)
                            | ((buf[start + 6] & 0xff) << 8) | (buf[start + 7] & 0xff);
                    if (xor) ip ^= MAGIC;
                    String addr = ((ip >>> 24) & 255) + "." + ((ip >>> 16) & 255) + "."
                            + ((ip >>> 8) & 255) + "." + (ip & 255);
                    return new Mapped(addr, port);
                }
            }
            b.position(start + l + ((4 - (l % 4)) % 4));
            attrLen++;
            if (attrLen > 32) break;
        }
        return null;
    }

    /** 判断是不是可用的公网地址（排除 0.0.0.0 / 私网 / 回环） */
    public static boolean isPublicV4(String ip) {
        if (ip == null) return false;
        String[] p = ip.split("\\.");
        if (p.length != 4) return false;
        int a = Integer.parseInt(p[0]), bq = Integer.parseInt(p[1]);
        if (a == 0 || a == 10 || a == 127) return false;
        if (a == 192 && bq == 168) return false;
        if (a == 172 && bq >= 16 && bq <= 31) return false;
        if (a == 169 && bq == 254) return false;
        if (a == 100 && bq >= 64 && bq <= 127) return false;   // CGNAT
        return true;
    }

    static List<String> servers() {
        List<String> l = new ArrayList<>();
        for (String s : SERVERS) l.add(s);
        return l;
    }
}
