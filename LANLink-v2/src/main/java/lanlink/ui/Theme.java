package lanlink.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/** 统一视觉：颜色、字体、圆角卡片、扁平按钮 */
public final class Theme {

    public static final Color BG        = new Color(0xF5F6FA);
    public static final Color CARD      = Color.WHITE;
    public static final Color INK       = new Color(0x0F172A);
    public static final Color MUTED     = new Color(0x6B7280);
    public static final Color LINE      = new Color(0xE7E9F0);
    public static final Color ACCENT    = new Color(0x4F46E5);
    public static final Color ACCENT2   = new Color(0x7C3AED);
    public static final Color OK        = new Color(0x059669);
    public static final Color WARN      = new Color(0xB45309);
    public static final Color ERR       = new Color(0xDC2626);
    public static final Color SOFT      = new Color(0xF3F4FA);

    private static final String[] PREFERRED = {"Microsoft YaHei UI", "微软雅黑", "PingFang SC", "Segoe UI", "Dialog"};

    private Theme() {}

    public static Font font(int size) { return new Font(pickFamily(), Font.PLAIN, size); }
    public static Font bold(int size) { return new Font(pickFamily(), Font.BOLD, size); }

    private static String cachedFamily;
    private static String pickFamily() {
        if (cachedFamily != null) return cachedFamily;
        List<String> available = new ArrayList<>();
        for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            available.add(f);
        }
        for (String p : PREFERRED) if (available.contains(p)) { cachedFamily = p; return p; }
        cachedFamily = "Dialog";
        return cachedFamily;
    }

    /** 圆角卡片 */
    public static class Card extends JPanel {
        private final int radius;
        private final Color fill;

        public Card(int radius) { this(radius, CARD); }

        public Card(int radius, Color fill) {
            this.radius = radius;
            this.fill = fill;
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(16, 24, 40, 8));
            g2.fill(new RoundRectangle2D.Float(0, 2, getWidth(), getHeight() - 2, radius, radius));
            g2.setColor(fill);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight() - 2, radius, radius));
            g2.setColor(LINE);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 3, radius, radius));
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** 扁平按钮：主要/次要/危险 */
    public static class Btn extends JButton {
        public enum Kind { PRIMARY, GHOST, DANGER, LINK }

        private final Kind kind;
        private boolean hover;

        public Btn(String text, Kind kind, int fontSize) {
            super(text);
            this.kind = kind;
            setFont(Theme.bold(fontSize));
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setCursor(new Cursor(Cursor.HAND_CURSOR));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(12, 22, 12, 22));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int r = kind == Kind.LINK ? 8 : 12;

            if (!isEnabled()) {
                g2.setColor(new Color(0xE9EBF2));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, r, r));
                g2.setColor(new Color(0x9AA1B1));
            } else if (kind == Kind.PRIMARY) {
                GradientPaint gp = new GradientPaint(0, 0, hover ? new Color(0x6D63F0) : new Color(0x5B52EA),
                        0, h, hover ? new Color(0x8B4BF0) : new Color(0x7C3AED));
                g2.setPaint(gp);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, r, r));
                g2.setColor(Color.WHITE);
            } else if (kind == Kind.DANGER) {
                g2.setColor(hover ? new Color(0xFDEBEB) : Color.WHITE);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, r, r));
                g2.setColor(new Color(0xF3C9C9));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, r, r));
                g2.setColor(ERR);
            } else if (kind == Kind.GHOST) {
                g2.setColor(hover ? new Color(0xE9EBF6) : SOFT);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, r, r));
                g2.setColor(new Color(0x475569));
            } else {
                g2.setColor(hover ? new Color(0xE2E6F5) : new Color(0xF0F2F9));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, r, r));
                g2.setColor(ACCENT);
            }

            FontMetrics fm = g2.getFontMetrics(getFont());
            String text = getText();
            int tx = (w - fm.stringWidth(text)) / 2;
            int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.setFont(getFont());
            g2.drawString(text, tx, ty);
            g2.dispose();
        }
    }

    /** 圆角输入框（用 JTextField 加内边距 + 自定义边框近似） */
    public static JTextField textField(int size) {
        JTextField f = new JTextField();
        f.setFont(Theme.font(size));
        f.setForeground(INK);
        f.setBackground(new Color(0xFBFCFE));
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE, 1, true),
                BorderFactory.createEmptyBorder(11, 14, 11, 14)));
        f.setCaretColor(ACCENT);
        return f;
    }
}
