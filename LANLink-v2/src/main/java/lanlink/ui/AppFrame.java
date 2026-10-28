package lanlink.ui;

import lanlink.core.Diagnostics;
import lanlink.core.Engine;
import lanlink.core.Invite;
import lanlink.core.NetInfo;
import lanlink.core.PhoneProfile;
import lanlink.core.Stun;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static lanlink.ui.Theme.*;

/**
 * 主界面内容：三个界面（首页 / 建房 / 加入），一步只让用户做一件事。
 *
 * 设计要点：继承 JPanel 而非 JFrame。
 * JFrame 的构造需要图形环境（无头模式下直接抛 HeadlessException），
 * 这会让「界面能否正常构建」这一关键问题无法在自动测试中验证 ——
 * 曾出现过 2116 项断言全过、程序却因一个未赋值的控件字段启动即崩的情况。
 * 改为 JPanel 后，界面可在无头环境下完整构建并遍历检查。
 * 窗口外壳（标题、尺寸、关闭行为）由 MainWindow 负责。
 */
public final class AppFrame extends JPanel {

    private final Engine engine = new Engine();
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);

    private final JLabel statusPill = new JLabel("未连接");
    private final JLabel statusSub = new JLabel(" ");

    private String myPublicIp;
    private String myLanIp;
    private Invite currentInvite;
    private boolean hosting;
    private String myVirtualIp;
    /** 分配给手机端的虚拟 IP，避开已被占用的地址 */
    private String phoneVirtualIp = "10.144.0.9";
    /** 是否处于"已建房、等待对方接入"状态。用状态位而不是匹配文案字符串，避免改文案就改坏逻辑。 */
    private volatile boolean waitingForPeers;
    /** 房主本次监听的端口，每次建房随机，避免公网上出现固定端口 */
    private volatile int hostListenPort;
    /** 最后一次发现成员的时间，用于判定对方离线 */
    private volatile long lastPeerSeen;
    /** 当前使用的公网 IP，用于检测地址漂移 */
    private volatile String activePublicIp;
    /** 是否具备管理员权限 */
    private final boolean admin = Diagnostics.isElevated();

    // 建房页控件
    private JLabel inviteCodeLabel;
    private JLabel ipLabel;
    private JLabel pwdLabel;
    private JLabel vIpLabel;
    private JLabel peerCountLabel;
    private JTextArea memberList;
    private JLabel hostHint;
    private Card phoneCard;
    private JLabel phoneNet;
    private JLabel phonePwd;
    private JLabel phonePeer;
    private JLabel phoneIp;
    private JLabel phoneLan;
    private JTextArea phoneToml;
    /** 手机端核对项（DHCP、第二台改号、节点一致性）—— 必须是界面文字，不能只写在 TOML 注释里 */
    private JPanel phoneTipBox;
    /** 配置二维码：手机扫一下即可拿到配置文本，省去「传文件 + 翻目录」 */
    private QrCard phoneQr;
    /** 配置正文折叠区：默认收起，避免把下面的按钮挤出屏幕 */
    private JPanel phoneRaw;
    private Btn phoneRawToggle;
    private JTextField relayField;
    private JTextField joinRelayField;
    private JCheckBox usePublicBox;
    private JCheckBox joinUsePublicBox;
    private JLabel usePublicHint;
    private JLabel joinUsePublicHint;
    private JCheckBox forceRelayBox;
    private JCheckBox joinForceRelayBox;
    private JLabel forceRelayHint;
    private JLabel joinForceRelayHint;
    private JLabel reachHint;

    /** 成员互通自检结果：虚拟 IP -> 往返毫秒（-1 表示超时） */
    private final java.util.Map<String, Integer> reachMs = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long lastReachSweep;

    // 加入页控件
    private JTextField joinCodeField;
    private JTextField joinIpField;
    private JTextField joinPwdField;
    private JLabel joinError;
    private Card joinResult;
    private JLabel guestHostIp;
    private JLabel guestSelfIp;
    private JTextArea guestMemberList;

    private final JTextArea logArea = new JTextArea();
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public AppFrame() {
        setOpaque(false);
        setLayout(new BorderLayout());
        setBackground(BG);

        engine.setLineListener(line -> {
            if (isNoise(line)) return;
            SwingUtilities.invokeLater(() -> appendLog(line));
        });

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(18, 22, 14, 22));

        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);
        root.add(buildLogPanel(), BorderLayout.SOUTH);
        add(root, BorderLayout.CENTER);
    }

    /** 供窗口壳调用：窗口关闭时先停内核，避免留下孤儿进程与残留虚拟网卡 */
    public void shutdown() {
        try { engine.stop(); } catch (Exception ignored) { }
    }

    /** 启动后台探测与定时刷新。放在窗口显示之后调用，避免无头测试里起线程。 */
    public void startBackgroundTasks() {
        detectNetworkAsync();
        new javax.swing.Timer(2000, e -> tick()).start();
        new javax.swing.Timer(60000, e -> recheckPublicIp()).start();
    }

    /* ================= 头部 ================= */

    private JComponent buildHeader() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(0, 4, 16, 4));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        left.setOpaque(false);
        left.add(new LogoIcon());
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel t = new JLabel("LANLink 联机助手");
        t.setFont(bold(20));
        t.setForeground(INK);
        JLabel s = new JLabel("无需注册 · 与其他设备组建同一虚拟局域网");
        s.setFont(font(12));
        s.setForeground(MUTED);
        titles.add(t);
        titles.add(s);
        left.add(titles);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        statusPill.setFont(bold(12));
        statusPill.setOpaque(true);
        statusPill.setBorder(new EmptyBorder(6, 14, 6, 14));
        statusPill.setBackground(new Color(0xF1F3F8));
        statusPill.setForeground(MUTED);
        statusPill.setAlignmentX(Component.RIGHT_ALIGNMENT);
        statusSub.setFont(font(11));
        statusSub.setForeground(MUTED);
        statusSub.setAlignmentX(Component.RIGHT_ALIGNMENT);
        right.add(statusPill);
        right.add(Box.createVerticalStrut(3));
        right.add(statusSub);

        p.add(left, BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private static final class LogoIcon extends JComponent {
        LogoIcon() { setPreferredSize(new Dimension(44, 44)); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setPaint(new GradientPaint(0, 0, new Color(0x6366F1), 44, 44, new Color(0x8B5CF6)));
            g2.fillRoundRect(0, 0, 44, 44, 13, 13);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawArc(13, 16, 20, 14, 0, -180);
            g2.drawArc(13, 14, 20, 14, 180, -180);
            g2.dispose();
        }
    }

    /* ================= 主体 ================= */

    private JComponent buildBody() {
        body.setOpaque(false);
        // 每个界面各自包一层滚动容器：内容超出窗口时可滚动查看，
        // 不会被裁掉（此前建房页内容很长，小屏笔记本上底部按钮直接看不见）。
        body.add(scrollable(buildHomeCard()), "home");
        body.add(scrollable(buildHostCard()), "host");
        body.add(scrollable(buildJoinCard()), "join");
        cards.show(body, "home");
        return body;
    }

    /**
     * 把内容包进滚动容器。
     * 视口强制视图与可视区同宽，避免出现横向滚动条；高度按内容自适应。
     */
    private static JComponent scrollable(JComponent content) {
        JScrollPane sp = new JScrollPane();
        FillWidthViewport vp = new FillWidthViewport();
        vp.setView(content);
        sp.setViewport(vp);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    /** 视口：视图宽度跟随可视区宽度，防止出现横向滚动条 */
    private static final class FillWidthViewport extends JViewport {
        @Override public void setSize(int w, int h) {
            super.setSize(w, h);
            Component v = getView();
            if (v instanceof JComponent) {
                JComponent c = (JComponent) v;
                Dimension p = c.getPreferredSize();
                c.setSize(w, Math.max(p.height, h));
            }
        }
    }

    private JComponent buildHomeCard() {
        Card c = new Card(18);
        c.setLayout(new BorderLayout());
        c.setBorder(new EmptyBorder(26, 26, 26, 26));

        JLabel q = new JLabel("请选择操作");
        q.setFont(bold(19));
        q.setForeground(INK);

        JPanel opts = new JPanel(new GridLayout(1, 2, 16, 0));
        opts.setOpaque(false);

        opts.add(bigOption("①  创建房间",
                "<html>本机作为主机运行游戏，<br>将邀请码发送至对方客户端。</html>",
                true, e -> beginHost()));
        opts.add(bigOption("②  加入房间",
                "<html>对方已创建房间，<br>粘贴其提供的邀请码以加入。</html>",
                false, e -> cards.show(body, "join")));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(q);
        top.add(Box.createVerticalStrut(6));
        JLabel sub = new JLabel("房间创建后，双方设备将接入同一虚拟局域网，可在游戏的局域网模式中互相发现。");
        sub.setFont(font(13));
        sub.setForeground(MUTED);
        top.add(sub);
        top.add(Box.createVerticalStrut(20));

        c.add(top, BorderLayout.NORTH);
        c.add(opts, BorderLayout.CENTER);

        JPanel foot = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        foot.setOpaque(false);
        foot.add(muted("首次运行时系统将请求管理员权限，请选择“允许”。"));
        c.add(foot, BorderLayout.SOUTH);
        return c;
    }

    private JComponent bigOption(String title, String desc, boolean primary, java.awt.event.ActionListener act) {
        Card c = new Card(16, primary ? new Color(0xF7F7FF) : CARD);
        c.setLayout(new BorderLayout());
        c.setBorder(new EmptyBorder(22, 22, 22, 22));
        c.setCursor(new Cursor(Cursor.HAND_CURSOR));

        JPanel inner = new JPanel();
        inner.setOpaque(false);
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        JLabel t = new JLabel(title);
        t.setFont(bold(18));
        t.setForeground(primary ? ACCENT : INK);
        JLabel d = new JLabel(desc);
        d.setFont(font(13));
        d.setForeground(MUTED);
        inner.add(t);
        inner.add(Box.createVerticalStrut(8));
        inner.add(d);
        inner.add(Box.createVerticalStrut(18));

        Btn b = new Btn(primary ? "创建房间" : "加入房间", primary ? Btn.Kind.PRIMARY : Btn.Kind.GHOST, 14);
        if (!primary) {
            b.setText("加入房间");
        }
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.addActionListener(act);
        inner.add(b);

        c.add(inner, BorderLayout.NORTH);
        c.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { act.actionPerformed(null); }
        });
        return c;
    }

    private JComponent buildHostCard() {
        Card c = new Card(18);
        c.setLayout(new BorderLayout());
        c.setBorder(new EmptyBorder(22, 26, 22, 26));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        top.add(leftLabel("请将以下邀请码发送至对方客户端", bold(17), INK));
        top.add(Box.createVerticalStrut(12));

        Card codeBox = new Card(12, new Color(0xF7F7FF));
        codeBox.setLayout(new BorderLayout(12, 0));
        codeBox.setBorder(new EmptyBorder(14, 16, 14, 16));
        inviteCodeLabel = new JLabel("正在生成…");
        inviteCodeLabel.setFont(new Font("Consolas", Font.BOLD, 17));
        inviteCodeLabel.setForeground(new Color(0x4338CA));
        inviteCodeLabel.setCursor(new Cursor(Cursor.TEXT_CURSOR));
        codeBox.add(inviteCodeLabel, BorderLayout.CENTER);
        Btn copy = new Btn("复制", Btn.Kind.PRIMARY, 13);
        copy.addActionListener(e -> {
            copyToClipboard(inviteCodeLabel.getText());
            copy.setText("已复制");
            new javax.swing.Timer(1400, ev -> { copy.setText("复制"); ((javax.swing.Timer) ev.getSource()).stop(); }).start();
        });
        codeBox.add(copy, BorderLayout.EAST);
        top.add(codeBox);
        top.add(Box.createVerticalStrut(10));

        JPanel manual = new JPanel(new GridLayout(2, 1, 0, 2));
        manual.setOpaque(false);
        ipLabel = leftLabel("对方 IP 地址：—", font(13), MUTED);
        pwdLabel = leftLabel("房间密码：—", font(13), MUTED);
        manual.add(ipLabel);
        manual.add(pwdLabel);
        top.add(manual);

        top.add(Box.createVerticalStrut(6));
        hostHint = leftLabel(" ", font(12), MUTED);
        top.add(hostHint);

        top.add(Box.createVerticalStrut(16));
        Card stat = new Card(12, SOFT);
        stat.setLayout(new BorderLayout(0, 8));
        stat.setBorder(new EmptyBorder(12, 16, 12, 16));

        JPanel statHead = new JPanel(new BorderLayout());
        statHead.setOpaque(false);
        vIpLabel = leftLabel("本机虚拟 IP：—", bold(15), INK);
        peerCountLabel = new JLabel("暂无其他成员接入");
        peerCountLabel.setFont(font(12));
        peerCountLabel.setForeground(MUTED);
        statHead.add(vIpLabel, BorderLayout.WEST);
        statHead.add(peerCountLabel, BorderLayout.EAST);
        stat.add(statHead, BorderLayout.NORTH);

        memberList = new JTextArea(3, 24);
        memberList.setEditable(false);
        memberList.setFont(font(13));
        memberList.setOpaque(false);
        memberList.setForeground(INK);
        memberList.setBorder(null);
        memberList.setLineWrap(false);
        memberList.setText("对方接入后将在此显示其虚拟 IP 地址，游戏联机时请填写该地址");
        memberList.setForeground(MUTED);
        stat.add(memberList, BorderLayout.CENTER);
        reachHint = leftLabel("<html><body style='width:520px;line-height:150%'>"
                + "<b>互通自检：</b>每次刷新都会逐台 ping 其他成员，结果显示在上面的成员行尾。"
                + "本机（10.144.0.1）不参与自检 —— ping 自己永远成功，"
                + "因为它走的是本机回环，无法证明虚拟网卡和隧道是否正常，只会给出虚假的「已连通」；"
                + "真正有意义的是能否访问到别人。"
                + "</body></html>", font(11), MUTED);
        stat.add(reachHint, BorderLayout.SOUTH);
        top.add(stat);

        // 手机端（EasyTier App）所需配置项
        phoneCard = new Card(12, new Color(0xFAFBFD));
        phoneCard.setLayout(new javax.swing.BoxLayout(phoneCard, javax.swing.BoxLayout.Y_AXIS));
        phoneCard.setBorder(new EmptyBorder(10, 14, 10, 14));
        JLabel phTitle = leftLabel("手机端配置（EasyTier App）", bold(12), MUTED);
        // 不能用 Consolas：它没有中文字形，中文会渲染成方块/问号
        Font mono12 = font(12);
        phoneNet = leftLabel("网络名称：—", mono12, INK);
        phonePwd = leftLabel("网络密码：—", mono12, INK);
        phonePeer = leftLabel("初始节点：—", mono12, INK);
        phoneLan = leftLabel("局域网地址：—", mono12, INK);
        phoneIp = leftLabel("本机 IPv4：10.144.0.9/24", mono12, INK);
        phoneCard.add(phTitle);
        phoneCard.add(Box.createVerticalStrut(4));
        phoneCard.add(phoneNet);
        phoneCard.add(phonePwd);
        phoneCard.add(phonePeer);
        phoneCard.add(phoneLan);
        phoneCard.add(phoneIp);

        // 核对项放在界面里。用户导入配置时不会打开文件，TOML 里的注释起不到提示作用。
        phoneTipBox = new JPanel();
        phoneTipBox.setOpaque(false);
        phoneTipBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        phoneTipBox.setLayout(new javax.swing.BoxLayout(phoneTipBox, javax.swing.BoxLayout.Y_AXIS));
        phoneCard.add(Box.createVerticalStrut(4));
        phoneCard.add(phoneTipBox);
        phoneCard.add(Box.createVerticalStrut(8));

        // 二维码：手机端没有扫码/剪贴板导入，只有文件选择器。
        // 二维码里放的是「带操作说明的完整文本」，扫到的内容直接粘进记事本存成 .toml 即可。
        phoneQr = new QrCard("手机扫描即可取到配置",
                "用手机相机或任意扫码 App 扫这个码，得到的是一段带说明的配置文本。<br>"
              + "<b>替代了「下载文件 → 传到手机 → 在手机里翻目录找文件」。</b>");
        phoneCard.add(phoneQr);
        phoneCard.add(Box.createVerticalStrut(8));

        phoneToml = new JTextArea(6, 24);
        phoneToml.setEditable(false);
        phoneToml.setFont(font(11));
        phoneToml.setOpaque(false);
        phoneToml.setForeground(new Color(0x334155));
        phoneToml.setBorder(null);
        phoneToml.setText("正在生成配置…");

        JPanel phoneBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        phoneBtns.setOpaque(false);
        Btn copyToml = new Btn("复制配置内容", Btn.Kind.PRIMARY, 12);
        copyToml.setToolTipText("复制带说明的完整文本：粘贴到微信发给手机，手机上存成 lanlink.toml 后导入");
        copyToml.addActionListener(e -> {
            copyToClipboard(phoneProfile == null ? phoneToml.getText()
                    : lanlink.core.PhoneConfigText.encode(phoneProfile));
            copyToml.setText("已复制");
            new javax.swing.Timer(1500, ev -> {
                copyToml.setText("复制配置内容");
                ((javax.swing.Timer) ev.getSource()).stop();
            }).start();
        });
        Btn saveToml = new Btn("保存为文件", Btn.Kind.GHOST, 12);
        saveToml.setToolTipText("生成 lanlink-phone.toml。仅在手机端确实需要「导入配置文件」时使用");
        saveToml.addActionListener(e -> savePhoneConfig());
        phoneBtns.add(copyToml);
        phoneBtns.add(saveToml);
        phoneCard.add(phoneBtns);

        // 折叠区：只放「要搬运的那几行文本」。
        // 建房页本身已经很长，把 TOML 正文常驻显示会把下面的按钮全部挤到屏幕外，
        // 而用户真正需要常驻的是「扫码」和「复制」两个动作，正文只在核对时才有用。
        phoneRawToggle = new Btn("展开配置正文", Btn.Kind.GHOST, 11);
        phoneRawToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        phoneRaw = new JPanel(new BorderLayout(0, 4));
        phoneRaw.setOpaque(false);
        phoneRaw.add(new JScrollPane(phoneToml) {{
            setBorder(BorderFactory.createLineBorder(LINE));
            setOpaque(false);
            getViewport().setOpaque(false);
            setPreferredSize(new Dimension(0, 150));
        }}, BorderLayout.CENTER);
        JLabel rawHint = leftLabel("<html><body style='width:520px'>"
                + "这里就是二维码里装的全文。若扫码不方便，直接复制它发到手机即可。</body></html>",
                font(11), MUTED);
        phoneRaw.add(rawHint, BorderLayout.SOUTH);
        phoneRaw.setVisible(false);
        phoneRawToggle.addActionListener(e -> {
            boolean show = !phoneRaw.isVisible();
            phoneRaw.setVisible(show);
            phoneRawToggle.setText(show ? "收起配置正文" : "展开配置正文");
            phoneCard.revalidate();
            phoneCard.repaint();
        });
        phoneCard.add(phoneRawToggle);
        phoneCard.add(phoneRaw);

        top.add(Box.createVerticalStrut(10));
        top.add(phoneCard);
        top.add(Box.createVerticalStrut(10));
        relayField = textField(13);
        relayField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { onRelayEdited(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { onRelayEdited(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { onRelayEdited(); }
        });
        top.add(buildRelayCard("追加共享中继节点（可选）", relayField));
        top.add(Box.createVerticalStrut(6));

        usePublicBox = new JCheckBox("使用社区公共节点（免费、无需注册，作为中继兜底）", true);
        styleCheck(usePublicBox);
        usePublicBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        usePublicHint = leftLabel("", font(11), MUTED);
        usePublicHint.setText(hintHtml(HINT_PUBLIC_ON));
        usePublicBox.addActionListener(e -> {
            boolean on = usePublicBox.isSelected();
            usePublicHint.setText(hintHtml(on ? HINT_PUBLIC_ON : HINT_PUBLIC_OFF));
            usePublicHint.setForeground(on ? MUTED : WARN);
            appendLog(on ? "已启用社区公共节点：直连失败时可经其转发"
                    : "已关闭社区公共节点：仅尝试点对点直连");
            refreshPhoneProfile();
        });
        top.add(usePublicBox);
        top.add(Box.createVerticalStrut(2));
        top.add(usePublicHint);
        top.add(Box.createVerticalStrut(6));

        forceRelayBox = new JCheckBox("强制统一中继（热点、校园网、运营商大内网推荐）", false);
        styleCheck(forceRelayBox);
        forceRelayBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        forceRelayHint = leftLabel("", font(11), MUTED);
        forceRelayHint.setText(hintHtml(HINT_FORCE_OFF));
        forceRelayBox.addActionListener(e -> {
            boolean on = forceRelayBox.isSelected();
            forceRelayHint.setText(hintHtml(on ? HINT_FORCE_ON : HINT_FORCE_OFF));
            forceRelayHint.setForeground(on ? WARN : MUTED);
            // 关键：勾选即刻重算手机端配置。此前只改了提示文案，
            // 已经生成的 TOML 仍是直连版 —— 手机导入后与电脑端参数不一致，
            // 表现就是「两端都显示在线却连不上」。
            refreshPhoneProfile();
            // 内核已经跑起来时，改这个勾选**只影响手机端配置**，电脑端内核不会跟着变。
            // 不把这句话说出来，用户就会以为「勾了就等于生效了」——
            // 这正是本次「手机连上中继却看不到电脑」的成因。
            if (engine != null && engine.isRunning()) {
                appendLog(on ? "已勾选强制统一中继：仅影响手机端配置，"
                                + "电脑端内核需重新建房才会按中继模式启动"
                            : "已取消强制统一中继：仅影响手机端配置，电脑端内核仍在原模式下运行");
                hostHint.setText("⚠ 模式已改变但内核未重启：请回到首页重新「创建房间」，"
                        + "否则两端参数不一致、互相看不到。");
                hostHint.setForeground(ERR);
            }
        });
        top.add(forceRelayBox);
        top.add(Box.createVerticalStrut(2));
        top.add(forceRelayHint);

        c.add(top, BorderLayout.NORTH);

        JPanel actions = new JPanel(new GridLayout(1, 3, 10, 0));
        actions.setOpaque(false);
        Btn diag = new Btn("复制诊断信息", Btn.Kind.GHOST, 13);
        diag.addActionListener(e -> {
            copyToClipboard(buildDiagnostics());
            diag.setText("已复制");
            new javax.swing.Timer(1500, ev -> {
                diag.setText("复制诊断信息");
                ((javax.swing.Timer) ev.getSource()).stop();
            }).start();
        });
        Btn test = new Btn("测试连通性", Btn.Kind.GHOST, 14);
        test.addActionListener(e -> runPingTest());
        Btn stop = new Btn("断开连接", Btn.Kind.DANGER, 14);
        stop.addActionListener(e -> stopAll());
        actions.add(diag);
        actions.add(test);
        actions.add(stop);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.setBorder(new EmptyBorder(18, 0, 0, 0));
        south.add(actions, BorderLayout.CENTER);
        c.add(south, BorderLayout.SOUTH);
        return c;
    }

    private JComponent buildJoinCard() {
        Card c = new Card(18);
        c.setLayout(new BorderLayout());
        c.setBorder(new EmptyBorder(22, 26, 22, 26));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        top.add(leftLabel("请粘贴对方提供的邀请码", bold(17), INK));
        top.add(Box.createVerticalStrut(2));
        top.add(leftLabel("手机端扫码得到的整段配置也可以直接粘进来，点「粘贴并识别」", font(11), MUTED));

        joinCodeField = textField(15);
        joinCodeField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        joinCodeField.setPreferredSize(new Dimension(400, 46));
        top.add(Box.createVerticalStrut(12));
        top.add(joinCodeField);
        top.add(Box.createVerticalStrut(12));

        JPanel rowBtn = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        rowBtn.setOpaque(false);
        Btn connect = new Btn("使用邀请码连接", Btn.Kind.PRIMARY, 14);
        connect.addActionListener(e -> beginJoinFromCode());
        // 反方向搬运：别人扫了二维码/发来了配置文本，可以直接贴进本框。
        // 这里不另开输入框 —— 同一个框既吃邀请码也吃整段配置，
        // 少一个框就少一次「不知道该填哪儿」。
        Btn paste = new Btn("粘贴并识别", Btn.Kind.GHOST, 14);
        paste.setToolTipText("把手机上扫到的配置文本整段粘进来，会自动认出房间并填入 IP 与密钥");
        paste.addActionListener(e -> beginJoinFromPastedConfig());
        Btn back = new Btn("返回", Btn.Kind.GHOST, 14);
        back.addActionListener(e -> cards.show(body, "home"));
        rowBtn.add(connect);
        rowBtn.add(Box.createHorizontalStrut(10));
        rowBtn.add(paste);
        rowBtn.add(Box.createHorizontalStrut(10));
        rowBtn.add(back);
        top.add(rowBtn);

        top.add(Box.createVerticalStrut(22));
        JSeparator sep = new JSeparator();
        sep.setForeground(LINE);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        top.add(sep);
        top.add(Box.createVerticalStrut(16));

        top.add(leftLabel("或：仅填写 IP 地址与房间密码", bold(14), INK));
        top.add(Box.createVerticalStrut(10));

        JPanel manual = new JPanel(new GridBagLayout());
        manual.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(0, 0, 0, 10);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;

        joinIpField = textField(14);
        joinIpField.setPreferredSize(new Dimension(240, 44));
        joinPwdField = textField(14);
        joinPwdField.setPreferredSize(new Dimension(130, 44));

        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0.68;
        manual.add(labeled("对方 IP 地址", joinIpField), gc);
        gc.gridx = 1; gc.weightx = 0.32;
        manual.add(labeled("房间密码（6 位）", joinPwdField), gc);
        top.add(manual);

        top.add(Box.createVerticalStrut(12));
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row2.setOpaque(false);
        Btn connect2 = new Btn("使用 IP 地址连接", Btn.Kind.GHOST, 14);
        connect2.addActionListener(e -> beginJoinFromFields());
        row2.add(connect2);
        top.add(row2);

        top.add(Box.createVerticalStrut(10));
        joinError = leftLabel(" ", font(12), ERR);
        top.add(joinError);
        top.add(Box.createVerticalStrut(10));
        joinRelayField = textField(13);
        top.add(buildRelayCard("追加共享中继节点（可选）", joinRelayField));
        top.add(Box.createVerticalStrut(6));

        joinUsePublicBox = new JCheckBox("使用社区公共节点（免费、无需注册，作为中继兜底）", true);
        styleCheck(joinUsePublicBox);
        joinUsePublicBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        joinUsePublicHint = leftLabel("", font(11), MUTED);
        joinUsePublicHint.setText(hintHtml(HINT_PUBLIC_ON));
        joinUsePublicBox.addActionListener(e -> {
            boolean on = joinUsePublicBox.isSelected();
            joinUsePublicHint.setText(hintHtml(on ? HINT_PUBLIC_ON : HINT_PUBLIC_OFF));
            joinUsePublicHint.setForeground(on ? MUTED : WARN);
        });
        top.add(joinUsePublicBox);
        top.add(Box.createVerticalStrut(2));
        top.add(joinUsePublicHint);
        top.add(Box.createVerticalStrut(6));

        joinForceRelayBox = new JCheckBox("强制统一中继（须与房主填同一个节点）", false);
        styleCheck(joinForceRelayBox);
        joinForceRelayBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        joinForceRelayHint = leftLabel("", font(11), MUTED);
        joinForceRelayHint.setText(hintHtml(HINT_FORCE_OFF));
        joinForceRelayBox.addActionListener(e -> {
            boolean on = joinForceRelayBox.isSelected();
            joinForceRelayHint.setText(hintHtml(on ? HINT_FORCE_ON : HINT_FORCE_OFF));
            joinForceRelayHint.setForeground(on ? WARN : MUTED);
        });
        top.add(joinForceRelayBox);
        top.add(Box.createVerticalStrut(2));
        top.add(joinForceRelayHint);

        c.add(top, BorderLayout.NORTH);
        c.add(buildJoinResult(), BorderLayout.CENTER);
        return c;
    }

    /** 统一复选框外观 */
    private void styleCheck(JCheckBox box) {
        box.setFont(font(12));
        box.setForeground(MUTED);
        box.setOpaque(false);
        box.setFocusPainted(false);
    }

    // 选项说明文案：勾选与不勾选各说清「做什么、什么时候用、有什么代价」，
    // 让不熟悉网络的用户能自己判断，而不是面对一个名词猜。
    private static final String HINT_PUBLIC_ON =
            "<b>已启用。</b>两台设备无法直接连通时，由社区志愿者维护的公共节点代为转发数据。"
          + "适合手机热点、校园网、公司网络、运营商大内网等打洞经常失败的环境。<br>"
          + "代价：数据要多绕一个节点，延迟略高，晚间高峰可能排队；节点属公益性质，可能随时失效。<br>"
          + "<b>注意：两端都要勾选才有效。</b>只有一端勾选，另一端仍只走直连，照样连不通。";

    private static final String HINT_PUBLIC_OFF =
            "<b>已关闭。</b>只尝试两台设备之间点对点直连，数据不经过任何第三方。"
          + "适合双方在同一路由器下，或已经确认能直连的情况，延迟最低。<br>"
          + "<b>风险：</b>如果双方都在运营商大内网里（手机流量、部分宽带），直连必然失败，"
          + "此时没有任何备用通道，联机会直接卡在「等待成员接入」。"
          + "不确定时建议保持开启。";

    private static final String HINT_FORCE_ON =
            "<b>已启用。</b>不再尝试打洞和直连，全部流量只走下面填写的这一个中继节点。"
          + "建链更快、更稳，适合确认直连不通的环境。<br>"
          + "<b>前提：三台设备必须填同一个节点地址。</b>"
          + "公共节点彼此独立，各自连不同节点时，彼此之间没有数据路径 —— "
          + "表现就是「都显示在线，但互相连不上」。<br>"
          + "代价：延迟比直连高一些；该节点失效时需更换节点。";

    private static final String HINT_FORCE_OFF =
            "<b>未启用。</b>先尝试点对点直连，失败后再退到中继，速度与成功率兼顾。"
          + "如果经常卡在「对方已上线但游戏连不上」，再改用强制统一中继。";

    /** JLabel 不支持自动换行，用固定宽度的 HTML 段落来折行 */
    private static String hintHtml(String body) {
        return "<html><body style='width:520px;line-height:150%'>" + body + "</body></html>";
    }

    /** 共享中继节点配置行：双方填写同一地址，直连失败时经该节点转发 */
    private Card buildRelayCard(String title, JTextField field) {
        // 防御：调用方若传入 null，这里自行创建，绝不让 add() 收到 null
        final JTextField f = field == null ? new JTextField(13) : field;
        Card c = new Card(12, new Color(0xFAFBFD));
        c.setLayout(new javax.swing.BoxLayout(c, javax.swing.BoxLayout.Y_AXIS));
        c.setBorder(new EmptyBorder(10, 14, 10, 14));
        JLabel t = leftLabel(title, bold(12), MUTED);
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        f.setToolTipText("中继节点地址，例如 tcp://1.2.3.4:11010；两端必须填完全相同的地址");

        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        row.add(f, BorderLayout.CENTER);
        Btn pick = new Btn("填入推荐节点", Btn.Kind.GHOST, 11);
        pick.setToolTipText("自动填入一个当前可用的公共节点。三台设备都点这个按钮，就会填到同一个节点");
        pick.addActionListener(e -> {
            pick.setEnabled(false);
            pick.setText("探测中…");
            new Thread(() -> {
                String node = Diagnostics.recommendedNode();
                SwingUtilities.invokeLater(() -> {
                    if (node != null) f.setText(node);
                    pick.setText(node == null ? "无可用节点" : "已填入");
                    pick.setEnabled(true);
                });
            }, "pick-node").start();
        });
        row.add(pick, BorderLayout.EAST);

        JLabel hint = leftLabel("<html><body style='width:440px'>"
                + "留空表示只用直连。<b>若要中继，两端必须填完全相同的地址。</b>"
                + "</body></html>", font(11), MUTED);
        c.add(t);
        c.add(Box.createVerticalStrut(4));
        c.add(row);
        c.add(hint);
        return c;
    }

    /** 连接成功后的结果块：把"游戏里该填哪个 IP"怼到用户脸上 */
    private JComponent buildJoinResult() {
        joinResult = new Card(12, new Color(0xF0FBF6));
        joinResult.setLayout(new javax.swing.BoxLayout(joinResult, javax.swing.BoxLayout.Y_AXIS));
        joinResult.setBorder(new EmptyBorder(14, 18, 14, 18));
        joinResult.setVisible(false);

        JLabel title = new JLabel("已连接，请在游戏中填入以下地址");
        title.setFont(bold(14));
        title.setForeground(OK);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        guestHostIp = new JLabel("10.144.0.1");
        guestHostIp.setFont(new Font("Consolas", Font.BOLD, 26));
        guestHostIp.setForeground(new Color(0x05634A));
        guestHostIp.setAlignmentX(Component.LEFT_ALIGNMENT);

        guestSelfIp = new JLabel("本机虚拟 IP：—");
        guestSelfIp.setFont(font(12));
        guestSelfIp.setForeground(MUTED);
        guestSelfIp.setAlignmentX(Component.LEFT_ALIGNMENT);

        guestMemberList = new JTextArea(2, 24);
        guestMemberList.setEditable(false);
        guestMemberList.setFont(font(12));
        guestMemberList.setOpaque(false);
        guestMemberList.setForeground(MUTED);
        guestMemberList.setBorder(null);
        guestMemberList.setAlignmentX(Component.LEFT_ALIGNMENT);
        guestMemberList.setText("局域网内其他成员将在此显示");

        joinResult.add(title);
        joinResult.add(Box.createVerticalStrut(4));
        joinResult.add(guestHostIp);
        joinResult.add(Box.createVerticalStrut(2));
        joinResult.add(guestSelfIp);
        joinResult.add(Box.createVerticalStrut(8));
        joinResult.add(guestMemberList);
        return joinResult;
    }

    private JComponent labeled(String text, JComponent field) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel l = new JLabel(text);
        l.setFont(font(12));
        l.setForeground(MUTED);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(l);
        p.add(Box.createVerticalStrut(5));
        p.add(field);
        return p;
    }

    private JComponent buildLogPanel() {
        Card c = new Card(14);
        c.setLayout(new BorderLayout());
        c.setBorder(new EmptyBorder(10, 14, 10, 14));
        c.setPreferredSize(new Dimension(100, 118));

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        JLabel t = new JLabel("运行日志");
        t.setFont(bold(12));
        t.setForeground(MUTED);
        JLabel more = new JLabel("（发生异常时可截图反馈）");
        more.setFont(font(11));
        more.setForeground(new Color(0x9AA1B1));
        head.add(t, BorderLayout.WEST);
        head.add(more, BorderLayout.CENTER);

        logArea.setEditable(false);
        logArea.setFont(font(11));
        logArea.setForeground(new Color(0x475569));
        logArea.setBackground(new Color(0xFAFBFD));
        logArea.setBorder(new EmptyBorder(6, 8, 6, 8));
        logArea.setLineWrap(true);

        JScrollPane sp = new JScrollPane(logArea);
        sp.setBorder(BorderFactory.createLineBorder(LINE));
        sp.getViewport().setBackground(new Color(0xFAFBFD));

        c.add(head, BorderLayout.NORTH);
        c.add(sp, BorderLayout.CENTER);
        return c;
    }

    /* ================= 行为 ================= */

    private void detectNetworkAsync() {
        new Thread(() -> {
            myLanIp = NetInfo.bestLanIp();
            Stun.Mapped m = Stun.query();
            myPublicIp = (m != null && Stun.isPublicV4(m.address)) ? m.address : null;
            if (myPublicIp == null) myPublicIp = myLanIp;
        }, "netinfo").start();
    }

    private void beginHost() {
        cards.show(body, "host");
        stopAllSilently();
        hosting = true;

        // 启动前预检：把失败变成一句人话 + 一个明确动作
        Diagnostics.Preflight pf = Diagnostics.preflight(engineDir(), admin);
        if (!pf.ok) {
            showPreflightFailure(pf);
            return;
        }

        final String secret = Invite.randomSecret();
        final String vip = Invite.VNET_PREFIX + "1";        // 房主地址固定
        final int listenPort = Diagnostics.pickPort(20000, 60000);
        myVirtualIp = vip;
        hostListenPort = listenPort;
        lastPeerSeen = 0;

        vIpLabel.setText("本机虚拟 IP：" + vip);
        memberList.setForeground(MUTED);
        memberList.setText("正在建立虚拟局域网连接…");
        peerCountLabel.setText("等待成员接入");
        hostHint.setText("正在探测公网 IP 地址…");
        hostHint.setForeground(MUTED);

        new Thread(() -> {
            // 等公网 IP 探测结束（最多 4 秒），避免把内网 IP 当成公网 IP 报给朋友
            for (int i = 0; i < 40 && myPublicIp == null; i++) sleep(100);
            String ip = myPublicIp != null ? myPublicIp : (myLanIp != null ? myLanIp : "127.0.0.1");
            boolean isPublic = Stun.isPublicV4(ip);

            final Invite inv;
            try {
                inv = Invite.parse(ip + ":" + listenPort + "#" + secret);
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> showEngineError(e));
                return;
            }

            // 关键：先启动内核，再刷界面。
            // 之前先 invokeLater 给 currentInvite 赋值、紧接着用 currentInvite 调 engine.start，
            // 而 invokeLater 是异步的 —— engine.start 拿到的是 null，抛 helpful NPE。
            boolean force = forceRelayBox.isSelected();
            String relay = relayField.getText() == null ? "" : relayField.getText().trim();
            if (force && relay.isEmpty()) {
                String auto = Diagnostics.recommendedNode();
                if (auto != null) {
                    relay = auto;
                    final String fill = auto;
                    SwingUtilities.invokeLater(() -> relayField.setText(fill));
                }
            }
            if (force && relay.isEmpty()) {
                final String msg = "已勾选「强制统一中继」，但没有探测到可用的公共节点。"
                        + "请取消勾选，或在上面手动填写一个节点地址。";
                SwingUtilities.invokeLater(() -> {
                    hostHint.setText(msg);
                    hostHint.setForeground(WARN);
                });
                return;
            }
            final String relayFinal = relay;
            final boolean forceFinal = force;
            try {
                engine.setRelayUri(relayFinal);
                engine.setNoP2p(forceFinal);
                // 强制中继时只用指定的那一个节点。若同时开启「自动挑 3 个公共节点」，
                // 三台设备会各自落到不同节点，而公共节点彼此独立 —— 等于没有共同通道。
                engine.setUsePublicNodes(forceFinal ? false : usePublicBox.isSelected());
                engine.start(Engine.Role.HOST, inv, vip, listenPort, false);
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> showEngineError(ex));
                return;
            }
            currentInvite = inv;
            waitingForPeers = true;
            phoneVirtualIp = pickPhoneIp();

            // 内核探测到的公网地址与 NAT 类型，比外部 STUN 更权威
            try { Thread.sleep(2500); } catch (InterruptedException ignored) { }
            Engine.StunInfo si = engine.stunInfo();
            String kernelIp = (si != null && !si.publicIp.isEmpty()) ? si.publicIp : null;
            final String finalIp = kernelIp != null ? kernelIp : ip;
            final boolean hardNat = si != null && si.isHardNat();
            final boolean mappingOk = engine.portMappingOk();
            if (kernelIp != null) activePublicIp = kernelIp;
            else activePublicIp = ip;

            SwingUtilities.invokeLater(() -> {
                inviteCodeLabel.setText(Invite.parse(finalIp + ":" + listenPort + "#" + secret).format());
                ipLabel.setText("对方 IP 地址：" + finalIp + ":" + listenPort);
                pwdLabel.setText("房间密钥：" + secret);
                // 手机端配置统一走 refreshPhoneProfile()，与复选框事件共用同一段逻辑。
                // 此前这里是另一份内联复制，勾选模式后不会重算，于是两份实现各说各话。
                hostPhoneIp = finalIp;
                hostPhoneSecret = secret;
                hostPhoneRelay = relayFinal;
                refreshPhoneProfile();

                if (hardNat && !mappingOk) {
                    hostHint.setText("当前为对称型 NAT 且路由器未开放端口，"
                            + "已启用社区公共节点中继，对方仍可正常接入（延迟略高）。");
                    hostHint.setForeground(WARN);
                } else if (hardNat) {
                    hostHint.setText("当前为对称型 NAT，端口映射已生效；"
                            + "同时已启用社区公共节点中继作为备用通道。");
                    hostHint.setForeground(WARN);
                } else if (!mappingOk) {
                    hostHint.setText("路由器未开放本机端口；已启用社区公共节点中继作为备用通道。");
                    hostHint.setForeground(WARN);
                } else {
                    hostHint.setText("对方可通过任意网络接入，请复制上方邀请码并发送。");
                    hostHint.setForeground(MUTED);
                }
                java.util.List<String> nodes = engine.activePublicNodes();
                if (!nodes.isEmpty()) {
                    appendLog("已启用公共节点中继：" + String.join(", ", nodes));
                }
                setStatus("房间创建中", new Color(0xEEF0FF), ACCENT);
                statusSub.setText("等待对方接入…");
                appendLog("网络环境：NAT=" + (si == null ? "未知" : si.udpNatType)
                        + "，端口映射=" + (mappingOk ? "成功" : "未生效（已启用中继兜底）"));
            });
        }, "host-start").start();
    }

    private void beginJoinFromCode() {
        String text = joinCodeField.getText();
        try {
            Invite inv = Invite.parse(text);
            doJoin(inv);
        } catch (IllegalArgumentException ex) {
            // 整段粘进来的是配置文本而不是邀请码时，别只报「IP 格式不正确」，
            // 顺手识别一次 —— 这是用户最容易走错的一步。
            if (tryJoinFromPastedConfig(text)) return;
            joinError.setText("⚠ " + ex.getMessage());
        }
    }

    /**
     * 「粘贴并识别」：把手机上扫到/微信收到的配置文本还原成邀请码。
     *
     * 这在整条链路里是反向的一环 —— 配置也能从手机再搬回电脑。
     * 识别出的字段会同时回填到手动输入框，用户能看见究竟认出了什么，
     * 而不是点一下连接、成功与否全凭运气。
     */
    private void beginJoinFromPastedConfig() {
        String text = joinCodeField.getText();
        if (text == null || text.trim().isEmpty()) {
            joinError.setText("⚠ 请先把配置文本粘贴到上面的输入框");
            return;
        }
        if (!tryJoinFromPastedConfig(text)) {
            joinError.setText("⚠ 没认出配置内容：需要包含 ipv4、[network_identity] 与至少一个 [[peer]]");
        }
    }

    /** @return true 表示已识别并开始连接 */
    private boolean tryJoinFromPastedConfig(String text) {
        lanlink.core.PhoneConfigText.Parsed p = lanlink.core.PhoneConfigText.parse(text);
        if (p.toml == null) return false;

        if (!p.problems.isEmpty()) {
            StringBuilder sb = new StringBuilder("⚠ 配置有缺项：");
            for (int i = 0; i < p.problems.size(); i++) {
                if (i > 0) sb.append("；");
                sb.append(p.problems.get(i));
            }
            joinError.setText(sb.toString());
        }
        if (p.ipv4 != null) joinIpField.setText(p.ipv4);
        if (p.invite == null) {
            if (p.firstPeer != null) {
                joinError.setText("⚠ 认得配置，但其中没有可用的对方地址（只有公共节点）");
            }
            return false;
        }
        joinPwdField.setText(p.invite.secret);
        joinCodeField.setText(p.invite.format());
        if (p.relay) {
            joinForceRelayBox.setSelected(true);
            joinForceRelayHint.setText(hintHtml(HINT_FORCE_ON));
            joinForceRelayHint.setForeground(WARN);
            joinUsePublicBox.setSelected(false);
        }
        joinError.setText("✓ 已识别：" + p.invite.displayHost()
                + (p.relay ? "（强制统一中继）" : "") + "，正在连接…");
        doJoin(p.invite);
        return true;
    }

    private void beginJoinFromFields() {
        try {
            Invite inv = Invite.of(joinIpField.getText(), joinPwdField.getText());
            doJoin(inv);
        } catch (IllegalArgumentException ex) {
            joinError.setText("⚠ " + ex.getMessage());
        }
    }

    private void doJoin(Invite inv) {
        joinError.setText(" ");
        joinCodeField.setText(inv.format());
        stopAllSilently();
        hosting = false;

        // 启动前预检
        Diagnostics.Preflight pf = Diagnostics.preflight(engineDir(), admin);
        if (!pf.ok) {
            showPreflightFailure(pf);
            return;
        }

        // B 方案：虚拟 IP 由密钥与本机指纹派生，重连不变
        myVirtualIp = Invite.deriveVirtualIp(inv.secret, NetInfo.fingerprint());
        int listenPort = Diagnostics.pickPort(20000, 60000);
        lastPeerSeen = 0;

        setStatus("连接中", new Color(0xFDF4E3), WARN);
        statusSub.setText("正在连接至 " + inv.host);

        boolean force = joinForceRelayBox.isSelected();
        String relay = joinRelayField.getText() == null ? "" : joinRelayField.getText().trim();
        if (force && relay.isEmpty()) {
            String auto = Diagnostics.recommendedNode();
            if (auto != null) {
                relay = auto;
                final String fill = auto;
                SwingUtilities.invokeLater(() -> joinRelayField.setText(fill));
            }
        }
        if (force && relay.isEmpty()) {
            joinError.setText("⚠ 已勾选强制中继，但没有可用节点；请手动填写房主提供的节点地址。");
            setStatus("未连接", new Color(0xF1F3F8), MUTED);
            return;
        }

        try {
            engine.setRelayUri(relay);
            engine.setNoP2p(force);
            // 与房主保持一致：强制中继时只挂这一个节点，不自动再挑别的。
            engine.setUsePublicNodes(force ? false : joinUsePublicBox.isSelected());
            engine.start(Engine.Role.JOINER, inv, myVirtualIp, listenPort, false);
            // 内核启动成功 ≠ 链路已建立，真正看到主机后才切换为「已连接」
            setStatus("连接中", new Color(0xFDF4E3), WARN);
            statusSub.setText("正在建立链路…");
            if (joinResult != null) {
                guestHostIp.setText("10.144.0.1");
                guestSelfIp.setText("本机虚拟 IP：" + myVirtualIp);
                guestMemberList.setForeground(MUTED);
                guestMemberList.setText("正在建立链路…");
                joinResult.setVisible(true);
                joinResult.getParent().revalidate();
            }
            scheduleConflictCheck();
        } catch (Exception ex) {
            showEngineError(ex);
            joinError.setText("⚠ " + humanError(ex.getMessage()));
            setStatus("未连接", new Color(0xF1F3F8), MUTED);
        }
    }

    /* ================= 稳定性机制 ================= */

    /** A 方案：加入后检查本机 IP 是否与其他成员冲突，冲突则换号重启 */
    private void scheduleConflictCheck() {
        new Thread(() -> {
            try {
                Thread.sleep(4500);
                engine.refreshPeers();
                for (Engine.Peer p : engine.peersDetailed()) {
                    if (p.ipv4.equals(myVirtualIp)) {
                        String next = Invite.nextVirtualIp(myVirtualIp);
                        SwingUtilities.invokeLater(() -> {
                            appendLog("检测到虚拟 IP 冲突，正在更换为 " + next);
                            hostHint.setText("检测到本机虚拟 IP 与其他成员重复，已自动更换。");
                            hostHint.setForeground(WARN);
                        });
                        engine.restartWithIp(next);
                        myVirtualIp = next;
                        return;
                    }
                }
            } catch (Exception ignored) { }
        }, "ip-conflict").start();
    }

    /** 公网地址漂移检测：地址变化后旧邀请码立即失效，必须提示重新发送 */
    private void recheckPublicIp() {
        if (!hosting || !engine.isRunning()) return;
        new Thread(() -> {
            Stun.Mapped m = Stun.query();
            if (m == null || !Stun.isPublicV4(m.address)) return;
            String now = m.address;
            String before = activePublicIp;
            if (before == null || now.equals(before)) return;
            activePublicIp = now;
            SwingUtilities.invokeLater(() -> {
                if (currentInvite != null) {
                    // 邀请码必须重建：currentInvite 里存的是旧公网地址，
                    // 只改 ipLabel 的文字会让「复制的邀请码」与「显示的地址」不一致。
                    try {
                        currentInvite = Invite.parse(now + ":" + hostListenPort + "#" + hostPhoneSecret);
                        inviteCodeLabel.setText(currentInvite.format());
                    } catch (RuntimeException ignored) {
                        // 地址格式异常时保留旧邀请码，只更新文字，避免界面直接炸掉
                    }
                    ipLabel.setText("对方 IP 地址：" + now + ":" + hostListenPort);
                    // 手机端配置同样依赖这个地址。此前这里只改了 phonePeer 一行文字，
                    // 二维码和可搬运文本都还是旧 IP —— 用户扫码得到的是一个已经失效的地址。
                    hostPhoneIp = now;
                    refreshPhoneProfile();
                    hostHint.setText("公网地址已变更，旧邀请码与旧二维码已失效，请重新复制发送。");
                    hostHint.setForeground(WARN);
                    appendLog("公网地址变更：" + before + " → " + now);
                }
            });
        }, "ip-drift").start();
    }

    /** 生成一份可复制的诊断报告，便于远程分析连接问题 */
    private String buildDiagnostics() {
        StringBuilder sb = new StringBuilder();
        Engine.StunInfo si = engine.stunInfo();
        sb.append("===== LANLink 网络诊断 =====\n");
        sb.append("时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date())).append("\n");
        sb.append("公网 IP  : ").append(activePublicIp == null ? "未知" : activePublicIp).append("\n");
        sb.append("局域网 IP: ").append(myLanIp == null ? "未知" : myLanIp).append("\n");
        sb.append("NAT 类型 : ").append(si == null ? "未知" : si.udpNatType)
                .append(" / ").append(si == null ? "未知" : si.tcpNatType).append("\n");
        sb.append("端口映射 : ").append(engine.portMappingOk() ? "成功" : "未生效").append("\n");
        sb.append("监听端口 : ").append(hostListenPort).append("\n");
        sb.append("中继节点 : ").append(engine.relayUri() == null ? "未设置" : engine.relayUri()).append("\n");
        sb.append("强制中继 : ").append(engine.noP2p() ? "是" : "否").append("\n");
        // 「界面显示」与「内核实际」分开列出：两者不同就是本次连不上的根因
        sb.append("内核实际 : 强制中继=").append(engine.runningNoP2p() ? "是" : "否")
          .append("  挂公共节点=").append(engine.runningUsePublicNodes() ? "是" : "否")
          .append("  中继=").append(engine.runningRelayUri() == null ? "无" : engine.runningRelayUri()).append("\n");
        sb.append("内核节点 : ").append(engine.runningPeers().isEmpty() ? "无"
                : String.join(", ", engine.runningPeers())).append("\n");
        if (phoneDivergence != null) {
            sb.append("!! 两端不一致: ").append(phoneDivergence).append("\n");
        }
        sb.append("本机虚拟IP: ").append(myVirtualIp).append("\n");
        sb.append("邀请码   : ").append(currentInvite == null ? "未创建" : currentInvite.format()).append("\n");
        sb.append("已见成员 : ").append(engine.peers().isEmpty() ? "无" : String.join(", ", engine.peers())).append("\n");
        for (Engine.Peer p : engine.peersDetailed()) {
            sb.append("  · ").append(p.ipv4).append("  ").append(p.linkText())
              .append("  ").append(p.latMs).append("ms  自检=")
              .append(reachText(p.ipv4).trim()).append("\n");
        }
        sb.append("内核日志 : ").append(engine.logFile() == null ? "未生成" : engine.logFile().getAbsolutePath()).append("\n");
        sb.append("--- 内核日志（末 30 行）---\n");
        for (String l : engine.logTail(30)) sb.append(l).append('\n');
        return sb.toString();
    }

    /** 引擎目录 */
    private static File engineDir() {
        File core = Engine.locateCore();
        return core == null ? null : core.getParentFile();
    }

    /** 预检失败：标题 + 说明 + 建议动作 */
    private void showPreflightFailure(Diagnostics.Preflight pf) {
        appendLog("预检未通过：" + pf.title + " —— " + pf.detail);
        JOptionPane.showMessageDialog(this,
                pf.title + "\n\n" + pf.detail + "\n\n建议操作：\n" + pf.action,
                pf.title, JOptionPane.WARNING_MESSAGE);
        setStatus("未连接", new Color(0xF1F3F8), MUTED);
        statusSub.setText(pf.title);
        cards.show(body, "home");
    }

    /** 把底层异常翻译成小白能懂的一句话 */
    static String humanError(String raw) {
        return Diagnostics.humanError(raw);
    }

    private void showEngineError(Exception ex) {
        String msg = String.valueOf(ex.getMessage());
        appendLog("启动失败：" + msg);
        if (msg.contains("找不到")) {
            JOptionPane.showMessageDialog(this,
                    "未找到内核文件 easytier-core.exe。\n请确认其位于程序目录下的 engine 文件夹内。",
                    "内核文件缺失", JOptionPane.ERROR_MESSAGE);
        } else {
            joinError.setText("⚠ " + msg);
        }
    }

    private void runPingTest() {
        String target = hosting ? firstPeer() : "10.144.0.1";
        if (target == null) {
            appendLog("连通性测试：暂无其他成员接入");
            hostHint.setText("暂无其他成员接入，请等待对方使用邀请码连接后重试。");
            hostHint.setForeground(WARN);
            return;
        }
        appendLog("连通性测试：正在探测 " + target);
        new Thread(() -> {
            boolean ok = ping(target);
            SwingUtilities.invokeLater(() -> {
                if (ok) {
                    setStatus("已连通", new Color(0xE7F7F1), OK);
                    statusSub.setText("与 " + target + " 连通，可在游戏中直接使用该地址");
                    hostHint.setText("连通性验证通过。可在游戏的局域网模式中选择，或直接使用 " + target);
                    hostHint.setForeground(OK);
                } else {
                    setStatus("未连通", new Color(0xFDF4E3), WARN);
                    statusSub.setText("无法与 " + target + " 建立连接");
                    hostHint.setText("连通性验证未通过。可能对方尚未接入，或被防火墙拦截。");
                    hostHint.setForeground(WARN);
                }
            });
        }, "ping").start();
    }

    private boolean ping(String ip) {
        try {
            ProcessBuilder pb = new ProcessBuilder("ping", "-n", "2", "-w", "1200", ip);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
            p.waitFor();
            return p.exitValue() == 0 || out.contains("TTL=") || out.toLowerCase().contains("ttl=");
        } catch (Exception e) {
            return false;
        }
    }

    private void tick() {
        if (!engine.isRunning()) {
            if (hosting || waitingForPeers) {
                setStatus("未连接", new Color(0xF1F3F8), MUTED);
                statusSub.setText("虚拟局域网连接已停止");
            }
            return;
        }
        if (refreshing.getAndSet(true)) return;
        new Thread(() -> {
            try {
                engine.refreshPeers();
            } finally {
                refreshing.set(false);
            }
            List<Engine.Peer> peers = engine.peersDetailed();
            sweepReachability(peers);
            SwingUtilities.invokeLater(() -> {
                renderMembers(peers);
                checkPeerOffline(peers);
            });
        }, "tick").start();
    }

    /**
     * 成员互通自检：逐台 ping 其他成员，验证「数据面」是否真的通。
     *
     * 为什么不探测本机：ping 自己的虚拟地址走的是本机回环，永远成功，
     * 既不能证明虚拟网卡工作正常，也不能证明隧道通 —— 只会报出一个虚假的「已连通」。
     * 真正有意义的是能否到达别人，所以只对成员列表里的其他设备做探测。
     *
     * 之所以需要自检：成员列表来自内核的 peer 表，它是「控制面」信息 ——
     * 两端经同一个中继节点注册、彼此可被看到，并不等于数据能互相送达。
     * 用户看到的「显示在同一局域网却连不上」，本质就是把「可见」当成了「可达」。
     */
    private void sweepReachability(List<Engine.Peer> peers) {
        if (peers.isEmpty()) {
            reachMs.clear();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReachSweep < 15000) return;
        lastReachSweep = now;
        java.util.List<String> ips = new java.util.ArrayList<>();
        for (Engine.Peer p : peers) ips.add(p.ipv4);
        new Thread(() -> {
            for (String ip : ips) reachMs.put(ip, pingMs(ip));
            SwingUtilities.invokeLater(() -> renderMembers(engine.peersDetailed()));
        }, "reach-check").start();
    }

    /** 单台探测：返回往返毫秒，-1 表示超时。中文与英文系统的 ping 输出都要认。 */
    private int pingMs(String ip) {
        try {
            ProcessBuilder pb = new ProcessBuilder("ping", "-n", "1", "-w", "1000", ip);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.Charset.defaultCharset());
            p.waitFor();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:time|时间|tiempo)[=<]\\s*(\\d+)\\s*ms", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(out);
            if (m.find()) return Integer.parseInt(m.group(1));
            return (p.exitValue() == 0 || out.toLowerCase().contains("ttl=")) ? 0 : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 成员行尾的互通结论：尚未探测 / 可达 nms / 不通 */
    private String reachText(String ip) {
        Integer ms = reachMs.get(ip);
        if (ms == null) return "   自检中…";
        if (ms < 0) return "   ✗ 不通（能看到，但数据到不了）";
        return ms == 0 ? "   ✓ 可达" : "   ✓ 可达 " + ms + "ms";
    }

    /**
     * 不要在内容没变时重设文本。
     *
     * 每 2 秒一次的 tick 都会调 renderMembers()，而 JTextArea.setText()
     * 会重建整个文档 → preferredSize 变化 → FillWidthViewport.setSize()
     * 重新给视图定尺寸 → 视口位置被夹回 0。
     * 用户正在看下面的按钮，就被拽回顶部，这是「每次自动刷新都跳回最上面」的根因。
     * 内容一致时直接跳过，绝大多数刷新就不会再动布局。
     */
    private static void setTextIfChanged(JTextArea area, String text) {
        if (area == null || text == null) return;
        if (text.equals(area.getText())) return;
        area.setText(text);
    }

    /** 对方离线判定：内核仍在运行但成员列表持续为空超过 10 秒 */
    private void checkPeerOffline(List<Engine.Peer> peers) {
        if (!hosting) return;
        if (!peers.isEmpty()) {
            lastPeerSeen = System.currentTimeMillis();
            return;
        }
        if (lastPeerSeen == 0) return;                 // 本次会话尚未有成员接入
        if (System.currentTimeMillis() - lastPeerSeen < 10000) return;
        if ("对方已离线".equals(statusPill.getText().replace("● ", ""))) return;
        setStatus("对方已离线", new Color(0xFDF4E3), WARN);
        statusSub.setText("等待对方重新接入…");
        waitingForPeers = true;
    }

    /** 成员列表：IP + 主机名 + 直连/中继 + 延迟 —— 用户就是靠这个 IP 在游戏里连 */
    private void renderMembers(List<Engine.Peer> peers) {
        if (!hosting) {
            renderGuestMembers(peers);
            return;
        }
        if (memberList == null) return;
        if (peers.isEmpty()) {
            memberList.setForeground(MUTED);
            setTextIfChanged(memberList, hosting
                    ? "朋友连进来后，这里会显示他的虚拟 IP（游戏里填这个）"
                    : "正在等待主机响应…");
            if (peerCountLabel != null) {
                peerCountLabel.setText(hosting ? "暂无其他成员接入" : "正在连接");
                peerCountLabel.setForeground(MUTED);
            }
            return;
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Engine.Peer p : peers) {
            if (shown++ >= 4) { sb.append("…共 ").append(peers.size()).append(" 个成员"); break; }
            sb.append(p.display()).append(reachText(p.ipv4)).append('\n');
        }
        memberList.setForeground(INK);
        setTextIfChanged(memberList, sb.toString().trim());

        if (peerCountLabel != null) {
            peerCountLabel.setText(peers.size() + " 个成员在线");
            peerCountLabel.setForeground(OK);
        }
        if (hosting && waitingForPeers) {
            setStatus("已连通", new Color(0xE7F7F1), OK);
            statusSub.setText(peers.size() + " 个成员已接入虚拟局域网");
            waitingForPeers = false;
        }
    }

    /** 加入方的成员列表：至少要有主机 10.144.0.1 */
    private void renderGuestMembers(List<Engine.Peer> peers) {
        if (guestMemberList == null) return;
        if (peers.isEmpty()) {
            guestMemberList.setForeground(MUTED);
            setTextIfChanged(guestMemberList, "主机 10.144.0.1 · 正在建立链路…");
            return;
        }
        // 真正看到对端才算连上，内核启动成功并不代表链路已建立
        if ("连接中".equals(statusPill.getText().replace("● ", ""))) {
            setStatus("已连接", new Color(0xE7F7F1), OK);
            statusSub.setText("本机虚拟 IP " + myVirtualIp + " · 主机 10.144.0.1");
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Engine.Peer p : peers) {
            if (n++ >= 4) { sb.append("…共 ").append(peers.size()).append(" 个成员"); break; }
            sb.append(p.display()).append(reachText(p.ipv4));
            if (n < peers.size() && n < 4) sb.append('\n');
        }
        guestMemberList.setForeground(INK);
        setTextIfChanged(guestMemberList, sb.toString());
    }

    private boolean isShowingStatusRunning() {
        return engine.isRunning();
    }

    private String firstPeer() {
        List<String> p = engine.peers();
        return p.isEmpty() ? null : p.get(0);
    }

    private void stopAll() {
        engine.stop();
        stopAllSilently();
        cards.show(body, "home");
        setStatus("未连接", new Color(0xF1F3F8), MUTED);
        statusSub.setText(" ");
        appendLog("连接已断开");
    }

    private void stopAllSilently() {
        engine.stop();
        waitingForPeers = false;
        reachMs.clear();
        lastReachSweep = 0;
        if (memberList != null) {
            memberList.setForeground(MUTED);
            setTextIfChanged(memberList, "对方接入后将在此显示其虚拟 IP 地址，游戏联机时请填写该地址");
        }
        if (peerCountLabel != null) {
            peerCountLabel.setText("暂无其他成员接入");
            peerCountLabel.setForeground(MUTED);
        }
        if (joinResult != null) joinResult.setVisible(false);
    }

    private void setStatus(String text, Color bg, Color fg) {
        statusPill.setText("● " + text);
        statusPill.setBackground(bg);
        statusPill.setForeground(fg);
    }

    private void appendLog(String line) {
        logArea.append(line + "\n");
        if (logArea.getDocument().getLength() > 20000) {
            logArea.setText(logArea.getText().substring(8000));
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private static boolean isNoise(String line) {
        String l = line.toLowerCase();
        return l.isBlank()
                || l.contains("warn") && l.contains("mtu")
                || l.startsWith("\u001b");
    }

    /** 分配给手机端的虚拟 IP：跳过已被其他成员占用的地址 */
    private String pickPhoneIp() {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (Engine.Peer p : engine.peersDetailed()) used.add(p.ipv4);
        for (int i = 9; i < 250; i++) {
            String ip = "10.144.0." + i;
            if (!used.contains(ip)) return ip;
        }
        return "10.144.0.9";
    }

    /**
     * 生成手机端可直接导入的 EasyTier 配置文件（TOML）。
     * 字段名与内核实际配置项一致，见 core 日志中的 TOML 结构。
     *
     * 关键：必须同时给出三类初始节点，缺一不可 ——
     *   1) 本机局域网地址：手机与电脑在同一路由器下时走内网直连，延迟最低、
     *      完全绕过 NAT（实测这是同一 WiFi 下唯一能通的方式）；
     *   2) 本机公网地址：跨网络时的直连尝试；
     *   3) 社区公共节点：直连与内网都失败时的中继通道。
     *      注意中继要求**双方都挂上公共节点**——只挂电脑一侧手机侧连不上，
     *      这正是此前「手机运行中但电脑看不到成员」的根因。
     */
    public static String buildPhoneToml(Invite inv, String hostIp, String secret,
                                        String lanIp, int listenPort, String phoneIp) {
        return buildPhoneToml(inv, hostIp, secret, lanIp, listenPort, phoneIp, null, false);
    }

    /**
     * @param relayNode  强制中继时唯一要连接的节点地址
     * @param forceRelay true = 跳过打洞与直连，只走 relayNode（适合同一套 CGNAT 环境）
     */
    public static String buildPhoneToml(Invite inv, String hostIp, String secret,
                                        String lanIp, int listenPort, String phoneIp,
                                        String relayNode, boolean forceRelay) {
        return buildPhoneProfile(inv, hostIp, secret, lanIp, listenPort, phoneIp,
                relayNode, forceRelay, java.util.Arrays.asList(Diagnostics.PUBLIC_NODES)).toToml();
    }

    /**
     * 手机端配置的唯一生成入口：TOML 与界面提示都从这里出，避免「提示说中继、
     * 文件里还是直连」这类两套逻辑不同步的问题。
     */
    public static PhoneProfile buildPhoneProfile(Invite inv, String hostIp, String secret,
                                                String lanIp, int listenPort, String phoneIp,
                                                String relayNode, boolean forceRelay,
                                                List<String> publicNodes) {
        if (forceRelay) {
            return PhoneProfile.relay(inv, hostIp, secret, lanIp, listenPort, phoneIp, relayNode);
        }
        return PhoneProfile.direct(inv, hostIp, secret, lanIp, listenPort, phoneIp, publicNodes);
    }

    /** 中继输入框每次改动都重算手机端配置（手工改节点时 TOML 必须同步跟上） */
    private void onRelayEdited() {
        if (relayField == null || !forceRelayBox.isSelected()) return;
        String v = relayField.getText() == null ? "" : relayField.getText().trim();
        if (!v.isEmpty()) hostPhoneRelay = v;
        refreshPhoneProfile();
        // 同理：改节点只改了「手机要连哪儿」，电脑端内核的 -p 在启动时就定了。
        // 两端节点不同 = 没有共同通道，必须让用户看见。
        if (engine != null && engine.isRunning()) {
            String kr = engine.runningRelayUri();
            if (kr != null && !kr.isEmpty() && !v.isEmpty() && !kr.equals(v)) {
                hostHint.setText("⚠ 中继节点已改为 " + v + "，但电脑端内核仍连 " + kr
                        + "：请重新「创建房间」让内核跟上，否则两端不会碰面。");
                hostHint.setForeground(ERR);
            }
        }
    }

    /** 当前房间手机端配置所依赖的三项状态，供复选框变更时原地重算 */
    private volatile String hostPhoneIp;
    private volatile String hostPhoneSecret;
    private volatile String hostPhoneRelay;
    /** 最近一次算出的手机端配置，保存/复制动作直接用它，保证与界面显示同源 */
    private volatile PhoneProfile phoneProfile;
    /** 非空表示「界面显示的模式」与「内核实际运行的模式」已分叉，需要用户重启内核 */
    private volatile String phoneDivergence;

    /**
     * 按当前模式重算手机端配置，并同步刷新界面文字与提示。
     *
     * 这是「配置随模式变化」的唯一实现点：建房完成、勾选/取消公共节点、
     * 勾选/取消强制中继、追加中继节点，都调用它 —— 界面显示什么、
     * 用户保存/复制的就是什么，手机端永远拿到与电脑端一致的参数。
     */
    private void refreshPhoneProfile() {
        if (hostPhoneSecret == null || currentInvite == null) return;
        boolean force = forceRelayBox != null && forceRelayBox.isSelected();
        String relay = hostPhoneRelay;
        if (force) {
            String typed = relayField == null || relayField.getText() == null
                    ? "" : relayField.getText().trim();
            if (!typed.isEmpty()) relay = typed;
        }
        boolean usePublic = usePublicBox == null || usePublicBox.isSelected();
        List<String> nodes = usePublic
                ? java.util.Arrays.asList(Diagnostics.PUBLIC_NODES)
                : java.util.Collections.emptyList();

        PhoneProfile p = buildPhoneProfile(currentInvite, hostPhoneIp, hostPhoneSecret,
                myLanIp, hostListenPort, phoneVirtualIp, relay, force, nodes);
        phoneProfile = p;

        if (phoneNet == null) return;
        phoneNet.setText("模式：" + p.mode.label + "（" + p.modeSummary() + "）");
        phonePwd.setText("房间密钥：" + hostPhoneSecret);
        phonePeer.setText("初始节点：" + (p.mode == PhoneProfile.Mode.RELAY
                ? (relay == null || relay.isEmpty() ? "（未取到可用节点）" : relay)
                : "内网 + 公网 + 公共节点，共 " + p.effectivePeers().size() + " 条"));
        phoneLan.setText(p.mode == PhoneProfile.Mode.RELAY
                ? "局域网地址：本模式不使用"
                : "局域网地址：" + (myLanIp == null ? "未知" : myLanIp + ":" + hostListenPort));
        phoneIp.setText("本机 IPv4：" + p.phoneIp + "/24");
        // 显示与搬运用「带说明的可搬运文本」：手机上只有记事本可用，
        // 裸 TOML 扫过去用户不知道该干什么，也没法判断哪一段要复制。
        String carry = lanlink.core.PhoneConfigText.encode(p);
        phoneToml.setText(carry);
        phoneToml.setCaretPosition(0);
        if (phoneQr != null) phoneQr.setText(carry);
        renderPhoneTips(p);
    }

    /**
     * 把核对项画在界面上 —— 这是必需的，因为用户导入 TOML 时不会打开文件读注释，
     * 写进文件的「第二台请改 10.144.0.10」实际上是写给了空气。
     */
    private void renderPhoneTips(PhoneProfile p) {
        if (phoneTipBox == null) return;
        phoneTipBox.removeAll();

        // 最优先显示「界面声称的模式」与「内核真实跑的模式」是否分叉。
        // 这条不一致会直接导致手机连上了却看不到任何人，必须在最前面、用最重的颜色。
        String diverge = null;
        if (engine.isRunning()) {
            diverge = p.divergenceHint(engine.runningNoP2p(), engine.runningUsePublicNodes(),
                    engine.runningRelayUri());
        }
        if (diverge != null) {
            phoneDivergence = diverge;
            JLabel l = leftLabel("<html><body style='width:520px;line-height:150%'><b>"
                    + diverge + "</b></body></html>", font(11), ERR);
            l.setBorder(new EmptyBorder(4, 6, 4, 6));
            l.setOpaque(true);
            l.setBackground(new Color(0xFEF2F2));
            phoneTipBox.add(l);
            phoneTipBox.add(Box.createVerticalStrut(4));
        } else {
            phoneDivergence = null;
        }

        for (PhoneProfile.Tip t : p.tips()) {
            Color c = "error".equals(t.level) ? ERR : "warn".equals(t.level) ? WARN : MUTED;
            JLabel l = leftLabel("<html><body style='width:520px;line-height:145%'>"
                    + ("info".equals(t.level) ? "· " : "！ ") + t.text + "</body></html>", font(11), c);
            phoneTipBox.add(l);
            phoneTipBox.add(Box.createVerticalStrut(2));
        }
        phoneTipBox.revalidate();
        phoneTipBox.repaint();
    }

    /** 保存手机端配置文件，便于通过微信等工具发送至手机后导入 */
    private void savePhoneConfig() {
        try {
            // 保存前再算一次：避免界面还是旧模式时把过期的 TOML 存到桌面
            refreshPhoneProfile();
            String home = System.getProperty("user.home", ".");
            File dir = new File(home, "Desktop");
            if (!dir.isDirectory()) dir = new File(home);
            File f = new File(dir, "lanlink-phone.toml");
            // 存文件要的是**纯 TOML**：手机端「导入配置文件」只认 TOML，
            // 把带分隔线的说明文本存成 .toml 会直接解析失败。
            // 界面里显示的是可搬运文本，两者刻意不同。
            java.nio.file.Files.write(f.toPath(),
                    phoneProfile == null ? phoneToml.getText().getBytes(java.nio.charset.StandardCharsets.UTF_8)
                            : phoneProfile.toToml().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            JOptionPane.showMessageDialog(this,
                    "配置文件已保存至：\n" + f.getAbsolutePath() + "\n\n"
                            + "可通过微信等工具发送至手机，在手机端点击「导入配置」选择该文件。\n"
                            + "导入后请按界面上列出的核对项检查 DHCP 与虚拟 IPv4。",
                    "配置文件已保存", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "保存失败：" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void copyToClipboard(String s) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
    }

    private static JLabel leftLabel(String text, Font f, Color c) {
        JLabel l = new JLabel(text);
        l.setFont(f);
        l.setForeground(c);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setFont(font(12));
        l.setForeground(new Color(0x9AA1B1));
        return l;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
