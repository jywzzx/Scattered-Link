package lanlink.ui;

import lanlink.core.QrCode;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

import static lanlink.ui.Theme.*;

/**
 * 二维码展示块：白底卡片 + 可点击放大。
 *
 * 放在建房页的目的是给手机一条「看一眼就能抄」的路径。
 * 手机端（EasyTier 官方 GUI）没有扫码入口，也没有剪贴板导入，
 * 只有「导入配置文件」= 文件选择器。二维码把「传文件 + 翻目录找文件」
 * 换成「扫一下 → 全选复制 → 存成 .toml → 导入」。
 */
final class QrCard extends JPanel {

    private final JLabel canvas = new JLabel();
    private final JLabel subtitle;
    private String pendingText;

    QrCard(String title, String subtitleHtml) {
        setOpaque(false);
        setLayout(new BorderLayout(12, 6));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        canvas.setPreferredSize(new Dimension(150, 150));
        canvas.setOpaque(true);
        canvas.setBackground(Color.WHITE);
        canvas.setBorder(BorderFactory.createLineBorder(LINE));
        canvas.setHorizontalAlignment(SwingConstants.CENTER);
        canvas.setVerticalAlignment(SwingConstants.CENTER);
        canvas.setCursor(new Cursor(Cursor.HAND_CURSOR));
        canvas.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { zoom(); }
        });

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel t = new JLabel(title);
        t.setFont(bold(13));
        t.setForeground(INK);
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitle = new JLabel("<html><body style='width:330px;line-height:150%'>"
                + subtitleHtml + "</body></html>");
        subtitle.setFont(font(11));
        subtitle.setForeground(MUTED);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(t);
        text.add(Box.createVerticalStrut(4));
        text.add(subtitle);

        add(canvas, BorderLayout.WEST);
        add(text, BorderLayout.CENTER);
    }

    /** 更新二维码内容；内容过长时显示降级提示而不是空白 */
    void setText(String content) {
        pendingText = content;
        if (content == null || content.isEmpty()) {
            canvas.setIcon(null);
            canvas.setText("<html><center><span style='font-size:10px;color:#9AA1B1'>"
                    + "建房后<br>显示二维码</span></center></html>");
            return;
        }
        BufferedImage img = QrCode.renderForWidth(content, 140);
        if (img == null) {
            canvas.setIcon(null);
            canvas.setText("<html><center><span style='font-size:10px;color:#B45309'>"
                    + "配置较长<br>无法生成码<br>请用复制</span></center></html>");
            return;
        }
        canvas.setText("");
        canvas.setIcon(new ImageIcon(img));
    }

    /** 点开看大图：用手机扫电脑屏幕时，小图经常对不上焦 */
    private void zoom() {
        if (pendingText == null || pendingText.isEmpty()) return;
        BufferedImage big = QrCode.renderForWidth(pendingText, 560);
        if (big == null) {
            JOptionPane.showMessageDialog(this,
                    "当前配置过长，无法生成可用的二维码。\n"
                            + "请改用下方「复制配置内容」按钮，把文本粘贴到微信发给手机，\n"
                            + "再在手机上存成 lanlink.toml 后导入。",
                    "无法生成二维码", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JLabel l = new JLabel(new ImageIcon(big));
        l.setBackground(Color.WHITE);
        l.setOpaque(true);
        JOptionPane.showMessageDialog(this, l, "手机扫码获取配置", JOptionPane.PLAIN_MESSAGE);
    }
}
