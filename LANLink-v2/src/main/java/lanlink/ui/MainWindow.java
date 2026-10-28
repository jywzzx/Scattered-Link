package lanlink.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * 窗口外壳。只负责标题、尺寸、位置与关闭行为。
 *
 * 界面内容全部在 AppFrame（JPanel）里 —— 这样界面可以在无头环境下构建并做
 * 自动检查，避免「逻辑自检全过、界面却启动即崩」这类问题漏到用户手上。
 */
public final class MainWindow extends JFrame {

    private final AppFrame app;

    public MainWindow() {
        super("LANLink 联机助手");

        app = new AppFrame();
        setContentPane(app);
        getContentPane().setBackground(app.getBackground());

        // 窗口尺寸必须按屏幕实际可用区域计算。
        // 之前写死 820×720，在 1366×768 或带任务栏的屏幕上会导致标题栏
        // 跑到屏幕外，关闭按钮点不到。
        java.awt.Rectangle screen = java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int w = Math.min(880, screen.width - 40);
        int h = Math.min(820, screen.height - 40);
        setMinimumSize(new Dimension(Math.min(700, w), Math.min(560, h)));
        setSize(w, h);
        // 放在屏幕可用区域内，而不是简单居中（居中可能超出上下边界）
        setLocation(screen.x + Math.max(0, (screen.width - w) / 2),
                    screen.y + Math.max(0, (screen.height - h) / 2));

        // 不能用 EXIT_ON_CLOSE：那样 JVM 立刻退出，内核子进程会变成孤儿进程，
        // 虚拟网卡留在系统里、端口也被占着，下次启动就会失败。
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                app.shutdown();
                dispose();
                System.exit(0);
            }
        });
    }

    /** 窗口显示后启动后台任务 */
    public void start() {
        app.startBackgroundTasks();
    }
}
