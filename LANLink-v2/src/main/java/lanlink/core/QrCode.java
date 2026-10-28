package lanlink.core;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/**
 * 手机配置二维码。
 *
 * 为什么值得做：手机端是 EasyTier 官方 GUI，它没有「扫一扫导入」也没有
 * 「从剪贴板读配置」，只有「导入配置文件」= 打开文件选择器。
 * 让用户把电脑桌面的 TOML 传到手机、再在手机里翻目录找到它，
 * 是整条链路里最容易失败的一步（路径找不到、微信文件下到 Download、
 * 文件名被改成 lanlink-phone(1).toml……）。
 *
 * 二维码承载的是**完整可搬运文本**（见 {@link PhoneConfigText}）而不是链接：
 * 手机用任意扫码 App 扫到的就是能直接复制保存的配置，省掉文件传输与目录查找两件事。
 *
 * 容量估算的坑：QR 规范里「版本 40 + 纠错 L = 2953 字节」是**纯字节模式**的上限。
 * 我们的文本是「中文说明 + ASCII 配置」混排，ZXing 会自动切成多段
 * （汉字约 3 字节/字符、数字按 10 bit/3 位压缩），实际能装下的 UTF-8 字节数
 * 明显低于 2953。这里按「ASCII 与多字节分别计费」做保守估算，
 * 宁可提前判定放不下、退回复制，也不要生成一张扫不出来的码。
 */
public final class QrCode {

    /** 保守估计：单张码最多承载的 UTF-8 字节数（含中文说明时远低于字节模式上限） */
    public static final int SAFE_BYTES = 1400;

    private QrCode() { }

    /**
     * 文本是否能稳妥地编码进一张二维码。
     *
     * 计费方式：ASCII 字符 1 分，非 ASCII 字符按「UTF-8 字节数」计分（3 或 4 分），
     * 总分不超过 {@link #SAFE_BYTES} 才认为稳。这样中文说明的代价被如实计入，
     * 不会出现「字节数看着没超、实际超版本」的情况。
     */
    public static boolean fits(String text) {
        return cost(text) > 0 && cost(text) <= SAFE_BYTES;
    }

    /** 估算容量占用（分）。返回 -1 表示空内容 */
    static int cost(String text) {
        if (text == null || text.isEmpty()) return -1;
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            n += c < 0x80 ? 1 : 3;
        }
        return n;
    }

    /**
     * 生成二维码。
     *
     * @param text   任意文本（UTF-8）
     * @param module 每个模块的像素边长，建议 4~8
     * @return RGB 图片；文本超长或编码失败时返回 null，调用方自行降级
     */
    public static BufferedImage render(String text, int module) {
        if (text == null || text.isEmpty()) return null;
        if (!fits(text)) return null;
        int m = Math.max(2, module);
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            // 纠错等级按内容长度自适应：短内容用 H（允许 30% 破损，手机斜着扫也认），
            // 长内容降到 L（同样模块数能装更多字符）。
            int cost = cost(text);
            ErrorCorrectionLevel ec = cost <= 260 ? ErrorCorrectionLevel.H
                    : cost <= 620 ? ErrorCorrectionLevel.M
                    : ErrorCorrectionLevel.L;
            hints.put(EncodeHintType.ERROR_CORRECTION, ec);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 2);

            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            BufferedImage img = new BufferedImage(w * m, h * m, BufferedImage.TYPE_INT_RGB);
            int black = 0xFF000000;
            int white = 0xFFFFFFFF;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = matrix.get(x, y) ? black : white;
                    for (int dy = 0; dy < m; dy++) {
                        for (int dx = 0; dx < m; dx++) {
                            img.setRGB(x * m + dx, y * m + dy, rgb);
                        }
                    }
                }
            }
            return img;
        } catch (Exception e) {
            return null;
        }
    }

    /** 给 JLabel 用的缩放版本：模块边长由目标像素宽反推，保证清晰且不糊 */
    public static BufferedImage renderForWidth(String text, int targetPx) {
        if (text == null || text.isEmpty()) return null;
        int modules = estimateModules(text);
        if (modules <= 0) return null;
        int module = Math.max(2, targetPx / modules);
        return render(text, module);
    }

    /**
     * 估算所需模块边长（含静区），仅用于算缩放，不需要精确。
     * 表按「字节模式容量」给出，因为它是同一版本的上限，用来定尺寸足够。
     */
    static int estimateModules(String text) {
        int len = text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
        int[] caps = {17, 32, 53, 78, 106, 134, 154, 192, 230, 271, 321, 367, 425,
                458, 520, 586, 644, 718, 792, 858, 929, 1003, 1091, 1171, 1273,
                1367, 1465, 1528, 1628, 1732, 1840, 1952, 2068, 2188, 2303, 2431,
                2563, 2699, 2809, 2953};
        for (int v = 0; v < caps.length; v++) {
            if (len <= caps[v]) return 21 + v * 4 + 4;
        }
        return -1;
    }
}
