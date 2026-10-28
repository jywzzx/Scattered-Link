package lanlink;

import lanlink.ui.AppFrame;

import javax.swing.*;
import java.io.File;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Optional;

/**
 * 入口。职责：提权 → 单开限制 → 拉起窗口。
 * 每一步都往 %LOCALAPPDATA%\LANLink\startup.log 记一行，出问题有据可查。
 */
public final class Main {

    private static FileLock lock;
    private static File logFile;

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        // 引擎自检：不弹窗，用 --no-tun 起一次真实内核，确认参数被接受、进程不退出
        if (args.length > 0 && "--enginetest".equals(args[0])) {
            int code = 1;
            lanlink.core.Engine eng = new lanlink.core.Engine();
            try {
                lanlink.core.Invite inv = lanlink.core.Invite.parse(
                        "127.0.0.1#" + lanlink.core.Invite.randomSecret());
                eng.start(lanlink.core.Engine.Role.HOST, inv, "10.144.0.1", 11010, true);
                Thread.sleep(9000);
                boolean alive = eng.isRunning();
                System.out.println("engine_alive=" + alive);
                System.out.println("room=" + inv.roomName());
                for (String l : eng.logTail(20)) System.out.println("LOG| " + l);
                code = alive ? 0 : 1;
            } catch (Exception e) {
                System.out.println("engine_error=" + e);
            } finally {
                eng.stop();
            }
            System.exit(code);
        }

        // 安装自检：不弹窗、不建网卡，只确认打包后的内核路径是对的
        if (args.length > 0 && "--check".equals(args[0])) {
            System.out.println("java   = " + System.getProperty("java.version"));
            System.out.println("app    = " + System.getProperty("jpackage.app-path"));
            System.out.println("core   = " + lanlink.core.Engine.locateCore());
            System.out.println("cli    = " + lanlink.core.Engine.locateCli());
            System.out.println("lanIp  = " + lanlink.core.NetInfo.bestLanIp());
            System.out.println("admin  = " + isElevated());
            System.exit(lanlink.core.Engine.locateCore() != null ? 0 : 2);
        }

        // 界面自检：在无头环境下构建整个界面并遍历组件树。
        // 曾出现「逻辑自检 2116 项全过、程序却因一个未赋值控件启动即崩」的情况，
        // 根因是自检从未构造过界面。打包产物应能用本模式验证界面可用。
        if (args.length > 0 && "--uitest".equals(args[0])) {
            System.setProperty("java.awt.headless", "true");
            int code = 0;
            try {
                lanlink.ui.AppFrame probe = new lanlink.ui.AppFrame();
                int panels = 0;
                java.util.List<java.awt.Component> nulls = new java.util.ArrayList<>();
                collect(probe, nulls);
                panels = countPanels(probe);
                System.out.println("ui_build = OK");
                System.out.println("panels   = " + panels);
                System.out.println("nulls    = " + nulls.size());
                for (java.awt.Component c : nulls) {
                    System.out.println("  NULL CHILD in " + c.getClass().getSimpleName());
                }
                if (!nulls.isEmpty()) code = 1;
            } catch (Throwable t) {
                System.out.println("ui_build = FAIL");
                System.out.println("error    = " + t);
                for (StackTraceElement el : t.getStackTrace()) System.out.println("    at " + el);
                code = 1;
            }
            System.exit(code);
        }

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            log("未捕获异常 [" + t.getName() + "] " + e);
            for (StackTraceElement el : e.getStackTrace()) log("    at " + el);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "程序发生异常：" + e.getMessage() + "\n\n日志文件：" + logFile,
                    "LANLink 错误", JOptionPane.ERROR_MESSAGE));
        });

        log("=== 启动 ===");
        log("exe    = " + System.getProperty("jpackage.app-path"));
        log("cmd    = " + ProcessHandle.current().info().command().orElse("?"));
        log("os     = " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        log("cwd    = " + System.getProperty("user.dir"));
        boolean admin = isElevated();
        log("admin  = " + admin);

        // 顺序很重要：**先提权，再取锁**。
        // 反过来的话，提权后的新进程会被旧进程还握着的锁挡住，表现为"启动一下就没了"。
        if (isWindows() && !admin) {
            log("未获得管理员权限，正在尝试拉起管理员实例…");
            if (relaunchElevated()) {
                log("已启动管理员实例，本进程退出");
                return;
            }
            log("自动提权失败（用户取消 UAC 或系统策略限制），以普通权限继续");
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "创建虚拟网卡需要管理员权限。\n\n"
                            + "请右键单击本程序并选择“以管理员身份运行”。\n"
                            + "若未出现权限提示，可能是系统策略禁止了本程序的自提权操作。",
                    "需要管理员权限", JOptionPane.WARNING_MESSAGE));
        }

        if (!acquireSingleInstance()) {
            log("已有实例在运行，退出");
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "LANLink 已在运行，请检查任务栏。", "已运行", JOptionPane.INFORMATION_MESSAGE));
            return;
        }
        log("单实例锁 OK");

        // 防火墙入站放行：首次弹窗被取消是"永远连不上"的头号原因
        if (admin) {
            try {
                boolean fw = lanlink.core.Diagnostics.addFirewallRules(appExe(),
                        lanlink.core.Engine.locateCore());
                log("防火墙入站规则：" + (fw ? "已添加" : "添加失败，可于系统设置中手动放行"));
            } catch (Exception e) {
                log("防火墙规则异常: " + e);
            }
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) { }

        SwingUtilities.invokeLater(() -> {
            try {
                lanlink.ui.MainWindow win = new lanlink.ui.MainWindow();
                win.setVisible(true);
                win.start();
                log("主窗口已显示");
                if (!admin) log("当前未获得管理员权限，创建虚拟网卡可能失败");
            } catch (Throwable e) {
                log("窗口创建失败: " + e);
                for (StackTraceElement el : e.getStackTrace()) log("    at " + el);
                // 不再 rethrow：rethrow 会让 JVM 直接退出，用户只看到「进不去软件」，
                // 看不到任何原因。这里给出明确提示并保留日志路径。
                try {
                    JOptionPane.showMessageDialog(null,
                            "程序界面初始化失败。\n\n"
                                    + "错误信息：" + e + "\n\n"
                                    + "详细信息已写入日志文件：\n" + logFile + "\n\n"
                                    + "请把该日志文件发给开发者，以便定位问题。",
                            "LANLink 启动失败", JOptionPane.ERROR_MESSAGE);
                } catch (Throwable ignored) { }
                System.exit(3);
            }
        });
    }

    /* ---------- 日志 ---------- */

    static void log(String msg) {
        try {
            if (logFile == null) {
                File dir = new File(System.getenv().getOrDefault("LOCALAPPDATA",
                        System.getProperty("java.io.tmpdir")), "LANLink");
                if (!dir.exists() && !dir.mkdirs()) return;
                logFile = new File(dir, "startup.log");
                if (logFile.exists() && logFile.length() > 512 * 1024) logFile.delete();
            }
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "  " + msg + "\r\n";
            try (FileWriter w = new FileWriter(logFile, StandardCharsets.UTF_8, true)) {
                w.write(line);
            }
        } catch (Exception ignored) { }
    }

    /* ---------- 界面自检辅助 ---------- */

    /** 遍历组件树，收集 null 子组件 —— Swing 的 add(null) 会直接抛 NPE */
    private static void collect(java.awt.Container c, java.util.List<java.awt.Component> out) {
        if (c == null) return;
        for (java.awt.Component k : c.getComponents()) {
            if (k == null) {
                out.add(c);
            } else if (k instanceof java.awt.Container) {
                collect((java.awt.Container) k, out);
            }
        }
    }

    /** 统计容器数量，用于确认三张卡片都已构建 */
    private static int countPanels(java.awt.Container c) {
        int n = 0;
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof java.awt.Container) n += 1 + countPanels((java.awt.Container) k);
        }
        return n;
    }

    /* ---------- 单开 ---------- */

    private static boolean acquireSingleInstance() {
        try {
            File dir = new File(System.getenv().getOrDefault("LOCALAPPDATA",
                    System.getProperty("java.io.tmpdir")), "LANLink");
            if (!dir.exists() && !dir.mkdirs()) return true;
            RandomAccessFile raf = new RandomAccessFile(new File(dir, "app.lock"), "rw");
            lock = raf.getChannel().tryLock();
            return lock != null;
        } catch (Exception e) {
            log("单实例锁异常（忽略）: " + e);
            return true;
        }
    }

    /* ---------- 权限 ---------- */

    private static boolean isElevated() {
        return lanlink.core.Diagnostics.isElevated();
    }

    /** 程序自身的可执行文件路径（用于防火墙规则） */
    private static File appExe() {
        String p = System.getProperty("jpackage.app-path");
        if (p != null) {
            File f = new File(p);
            if (f.isFile()) return f;
        }
        return null;
    }

    /**
     * 用 PowerShell 的 Start-Process -Verb RunAs 拉起自己。
     *
     * 刻意**不写成 .ps1 文件再 -File 执行** —— 之前踩过：
     * 路径里有中文（D:\局域网联机软件\...），文件按 UTF-8 写、PowerShell 5.1 按 GBK 读，
     * 路径变乱码 → 找不到文件 → 提权失败 → 程序直接退出。
     * 改成 `-Command` 直传：命令行是 UTF-16，中文路径不会坏。
     */
    private static boolean relaunchElevated() {
        try {
            String exe = System.getProperty("jpackage.app-path");
            if (exe == null || !new File(exe).isFile()) {
                Optional<String> c = ProcessHandle.current().info().command();
                if (c.isPresent()) {
                    String p = c.get().toLowerCase();
                    if (!p.endsWith("java.exe") && !p.endsWith("javaw.exe")) exe = c.get();
                }
            }
            if (exe == null || !new File(exe).isFile()) {
                log("无法确定自身 exe 路径，跳过自动提权");
                return false;
            }
            log("提权目标: " + exe);

            String quoted = exe.replace("'", "''");
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile",
                    "-WindowStyle", "Hidden", "-Command",
                    "Start-Process -FilePath '" + quoted + "' -Verb RunAs");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.Charset.defaultCharset()).trim();
            int code = p.waitFor();
            log("提权返回 code=" + code + (out.isEmpty() ? "" : " out=" + out));
            return code == 0;
        } catch (Exception e) {
            log("提权异常: " + e);
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private Main() { }
}
