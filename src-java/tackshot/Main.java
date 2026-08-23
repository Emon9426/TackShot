package tackshot;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JWindow;
import javax.swing.Timer;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.HashMap;
import java.util.Map;

/** 钉图 TackShot V2.0（Java 版）入口：单实例 / 托盘 / 热键 / 输出分发 / 冒烟测试。 */
public final class Main {
    public static final String VERSION = "钉图 TackShot v2.2";
    public static Cfg cfg = new Cfg();
    public static String exeDir = ".";
    public static String exePath = "";
    public static JFrame hidden;
    static TrayIcon tray;
    static Hotkeys hotkeys;
    static boolean testMode, captureMode;
    private static RandomAccessFile lockFile;
    private static FileLock fileLock;

    private Main() {}

    static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    public static void main(String[] args) {
        // 必须在加载任何 AWT 类之前设置：关闭 AWT 逻辑缩放，全程物理像素（与 C++ 版同一坐标模型）
        System.setProperty("sun.java2d.uiScale.enabled", "false");
        resolveExe();
        for (String a : args) {
            String lo = a.toLowerCase();
            if (lo.equals("/test") || lo.equals("--test")) testMode = true;
            else if (lo.equals("/capture") || lo.equals("--capture")) captureMode = true;
        }
        if (!acquireSingleInstance()) {
            JOptionPane.showMessageDialog(null, "钉图 TackShot 已在运行（请查看系统托盘）。",
                    "钉图 TackShot", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        cfg.load();
        Log.write("==== 启动 " + VERSION + "（" + (testMode ? "测试模式" : "正常") + "）====");
        SwingUtilities.invokeLater(() -> initEdt());
    }

    private static void resolveExe() {
        try {
            java.net.URL u = Main.class.getProtectionDomain().getCodeSource().getLocation();
            File f = new File(u.toURI());
            exePath = f.getAbsolutePath();
            exeDir = f.getParentFile() != null ? f.getParentFile().getAbsolutePath() : ".";
        } catch (Throwable t) {
            exeDir = System.getProperty("user.dir");
            exePath = exeDir + File.separator + "TackShot.jar";
        }
        Log.dir = exeDir;
    }

    /** 单实例：jar 同目录 tackshot.lock 文件锁（exeDir 来自 code source，路径受控）。 */
    private static boolean acquireSingleInstance() {
        try {
            File f = new File(exeDir, "tackshot.lock");
            lockFile = new RandomAccessFile(f, "rw");
            fileLock = lockFile.getChannel().tryLock();
            return fileLock != null;
        } catch (Throwable t) {
            return true;   // 无法加锁时放行，不阻断使用
        }
    }

    private static void initEdt() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        scaleDialogFonts();
        Log.write("UI 字体族：" + Tb.UI_FAMILY + "（FR-6.8 非中文系统字体回退）");
        hidden = new JFrame();
        hidden.setUndecorated(true);
        hidden.setType(Frame.Type.UTILITY);
        setupTray();
        hotkeys = Hotkeys.start(cfg, id -> {
            switch (id) {
                case Hotkeys.HK_REGION: Capture.startRegion(); break;
                case Hotkeys.HK_FULL: Capture.startFullscreen(); break;
                case Hotkeys.HK_PIN: pinFromClipboard(); break;
                default: break;
            }
        });
        if (testMode) {
            runSmokeTest();
        } else {
            balloon("钉图 TackShot 已就绪", "Ctrl+Alt+A 开始截图 · 右键托盘图标查看菜单");
            if (captureMode) {
                Timer t = new Timer(300, (ActionEvent e) -> Capture.startRegion());
                t.setRepeats(false);
                t.start();
            }
        }
    }

    /** 物理像素模型下 Swing 对话框字体按主屏 DPI 放大（自绘 UI 不受影响）。
     *  LAF 默认字体（Segoe UI 等）无中文字形，先统一换成探测到的 CJK 字体族（FR-6.8）。 */
    private static void scaleDialogFonts() {
        try {
            float s = Toolkit.getDefaultToolkit().getScreenResolution() / 96f;
            Map<Object, Object> scaled = new HashMap<>();
            for (Object k : UIManager.getLookAndFeelDefaults().keySet().toArray()) {
                Object v = UIManager.getLookAndFeelDefaults().get(k);
                if (v instanceof Font) {
                    Font f = (Font) v;
                    Font base = f.canDisplay(Tb.CJK_PROBE) ? f
                            : new Font(Tb.UI_FAMILY, f.getStyle(), f.getSize());
                    if (s > 1.01f) scaled.put(k, base.deriveFont(base.getSize2D() * s));
                    else if (base != f) scaled.put(k, base);
                }
            }
            for (Map.Entry<Object, Object> en : scaled.entrySet())
                UIManager.put(en.getKey(), en.getValue());
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 托盘 ----------------

    /** 承载托盘弹出菜单的置顶窗（菜单保持轻量级，绘制走本窗 Java2D 路径）。 */
    private static JWindow trayHost;

    private static void setupTray() {
        if (!SystemTray.isSupported()) {
            Log.write("警告：系统不支持托盘");
            return;
        }
        try {
            // FR-6.8 V2.0.4：不设 AWT 原生 PopupMenu（其自绘文本在英文系统即使 setFont 仍方框），
            // 右键呼出 Swing 自绘菜单（与悬浮提示同一 Java2D 渲染路径，实测正常）
            tray = new TrayIcon(Icon.appIcon(), VERSION + " · Ctrl+Alt+A 截图");
            tray.setImageAutoSize(true);
            tray.addActionListener(e -> Capture.startRegion());        // 双击＝区域截图
            tray.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.isPopupTrigger()) showTrayMenu();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger()) showTrayMenu();
                }
            });
            SystemTray.getSystemTray().add(tray);
        } catch (AWTException e) {
            Log.write("警告：托盘图标创建失败");
        }
    }

    /** 在托盘图标上方弹出 Swing 菜单；承载窗与菜单等尺寸使弹出保持轻量（不脱离本窗置顶层）。 */
    private static void showTrayMenu() {
        if (trayHost != null) return;    // 已打开
        JPopupMenu m = buildTrayMenu();
        Dimension ps = m.getPreferredSize();
        Point p = MouseInfo.getPointerInfo().getLocation();
        Rectangle scr = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (gd.getDefaultConfiguration().getBounds().contains(p)) {
                scr = gd.getDefaultConfiguration().getBounds();
                break;
            }
        }
        int x = p.x - ps.width + 24;     // 右缘对齐托盘图标
        x = Math.max(scr.x + 4, Math.min(x, scr.x + scr.width - ps.width - 4));
        int y = p.y - ps.height - 12;    // 任务栏通常在下方：菜单悬于图标上方
        if (y < scr.y + 4) y = p.y + 12;
        y = Math.max(scr.y + 4, Math.min(y, scr.y + scr.height - ps.height - 4));
        JWindow host = new JWindow();
        host.setAlwaysOnTop(true);
        host.setBounds(x, y, ps.width, ps.height);
        host.setVisible(true);
        host.requestFocus();             // 承接 Esc / 方向键
        trayHost = host;
        m.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {}

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                host.dispose();          // 选中项 / Esc / 点击外部统一在此清理
                if (trayHost == host) trayHost = null;
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });
        m.show(host, 0, 0);
    }

    private static JPopupMenu buildTrayMenu() {
        float ms = Toolkit.getDefaultToolkit().getScreenResolution() / 96f;
        Font f = new Font(Tb.UI_FAMILY, Font.PLAIN, (int) (12f * ms + 0.5f));
        JPopupMenu m = new JPopupMenu();
        JMenuItem mi;
        mi = new JMenuItem("区域截图  Ctrl+Alt+A");
        mi.setFont(f);
        mi.addActionListener(e -> Capture.startRegion());
        m.add(mi);
        mi = new JMenuItem("全屏截图  Ctrl+Alt+F");
        mi.setFont(f);
        mi.addActionListener(e -> Capture.startFullscreen());
        m.add(mi);
        mi = new JMenuItem("贴图（剪贴板）  Ctrl+Alt+P");
        mi.setFont(f);
        mi.addActionListener(e -> pinFromClipboard());
        m.add(mi);
        m.addSeparator();
        mi = new JMenuItem("设置…");
        mi.setFont(f);
        mi.addActionListener(e -> Settings.showDialog());
        m.add(mi);
        JCheckBoxMenuItem mAuto = new JCheckBoxMenuItem("开机自启动", Nat.autoRunEnabled());
        mAuto.setFont(f);
        mAuto.addItemListener(e -> Nat.setAutoRun(mAuto.getState()));
        m.add(mAuto);
        m.addSeparator();
        mi = new JMenuItem("关于 钉图 TackShot");
        mi.setFont(f);
        mi.addActionListener(e -> JOptionPane.showMessageDialog(hidden,
                VERSION + "（Java 版）\n轻量级开源截图 · 贴图工具\n\n"
                        + "许可证：MIT（见 LICENSE 与 THIRD-PARTY-NOTICES）\n"
                        + "默认热键：Ctrl+Alt+A 区域 / Ctrl+Alt+F 全屏 / Ctrl+Alt+P 贴图\n\n"
                        + "本软件完全离线运行，不收集任何数据。",
                "关于", JOptionPane.INFORMATION_MESSAGE));
        m.add(mi);
        mi = new JMenuItem("退出");
        mi.setFont(f);
        mi.addActionListener(e -> quit());
        m.add(mi);
        m.pack();
        return m;
    }

    static void balloon(String title, String text) {
        if (tray != null) tray.displayMessage(title, text, TrayIcon.MessageType.INFO);
    }

    // ---------------- 输出分发 ----------------

    static void pinFromClipboard() {
        BufferedImage bmp = Clip.fromClipboard();
        if (bmp != null) Pin.create(bmp);
        else balloon("钉图 TackShot", "剪贴板中没有图片");
    }

    /** 确认（✓/Enter/双击/贴图按钮）输出分发：V2.0.3 起不写剪贴板，写剪贴板仅限复制/保存按钮。 */
    static void finishImage(BufferedImage img) {
        if (img == null) return;
        String msg;
        if (cfg.confirmAction.equals("copy_save")) {
            String path = saveAuto(img);
            msg = path.isEmpty() ? "自动保存失败" : "已保存 " + path;
        } else {
            if (cfg.confirmAction.equals("copy"))   // “仅复制”确认已不写剪贴板，按默认贴图处理
                Log.write("confirm_action=copy 不再写剪贴板，按默认贴图处理");
            Pin.create(img);
            msg = "已贴图";
        }
        balloon("钉图 TackShot", msg);
        Log.write("输出完成：" + msg);
    }

    private static String saveAuto(BufferedImage img) {
        String dir = cfg.outputDir.isEmpty() ? Out.defaultSaveDir() : cfg.outputDir;
        boolean jpeg = cfg.format.equals("jpeg");
        String path = Out.buildSavePath(dir, jpeg ? "jpg" : "png");
        return Out.save(img, path, jpeg, cfg.jpegQuality) ? path : "";
    }

    // ---------------- 退出 ----------------

    static void quit() {
        if (hotkeys != null) hotkeys.shutdown();
        Pin.closeAll();
        if (trayHost != null) {
            JWindow h = trayHost;
            trayHost = null;
            h.dispose();
        }
        if (tray != null) SystemTray.getSystemTray().remove(tray);
        cfg.save();
        Log.write("==== 退出 ====");
        System.exit(0);
    }

    // ---------------- 冒烟测试（/test）：保存 / 剪贴板 / 贴图链路 ----------------

    private static void runSmokeTest() {
        Log.write("==== TEST 开始 ====");
        int pass = 0, total = 0;

        final int W = 240, H = 140;
        BufferedImage bmp = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int r = x * 255 / W, g = y * 255 / H;
                bmp.setRGB(x, y, 0xFF000000 | (r << 16) | (g << 8) | 128);
            }
        String png = exeDir + "\\test_out.png";
        total++;
        if (Out.save(bmp, png, false, 90) && new File(png).exists()) {
            Log.write("TEST PASS: PNG 保存 (" + png + ")");
            pass++;
        } else Log.write("TEST FAIL: PNG 保存");

        total++;
        BufferedImage back = null;
        try {
            if (Clip.toClipboard(null, bmp)) back = Clip.fromClipboard();
        } catch (Throwable t) {
            Log.write("TEST 剪贴板异常：" + t);
        }
        // 回读 + 像素校验 + AWT 跨通道校验：仅查格式可用性无法发现"外部程序读不出"的回归（CF_DIB 曾误写为 CF_BITMAP）
        boolean awtOk = false;
        try {
            Object awtImg = Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getContents(null).getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor);
            awtOk = awtImg instanceof java.awt.Image;
        } catch (Throwable t) {
            Log.write("TEST AWT 剪贴板读取异常：" + t);
        }
        if (back != null && back.getWidth() == W && back.getHeight() == H
                && back.getRGB(0, 0) == bmp.getRGB(0, 0)
                && back.getRGB(W - 1, H - 1) == bmp.getRGB(W - 1, H - 1)
                && Nat.U32.I.IsClipboardFormatAvailable(Clip.CF_DIB) && awtOk) {
            Log.write("TEST PASS: 剪贴板写入（CF_DIB 回读像素一致，AWT 跨通道可读）");
            pass++;
        } else Log.write("TEST FAIL: 剪贴板写入（回读=" + (back == null ? "null" : back.getWidth() + "x" + back.getHeight())
                + "，AWT=" + awtOk + "）");

        total++;
        Pin.create(bmp);
        int n = Pin.count();
        boolean vis = n == 1 && !Pin.pins.isEmpty() && Pin.pins.get(0).isShown();
        if (n == 1 && vis) {
            Log.write("TEST PASS: 贴图创建且窗口可见（" + n + " 个）");
            pass++;
        } else {
            Log.write("TEST FAIL: 贴图创建（" + n + " 个）/ 可见性（"
                    + (vis ? "可见" : "不可见") + "）");
        }

        Log.write("TEST 结果：" + pass + "/" + total + " 通过");
        Log.write("==== TEST 结束（1.5 秒后退出） ====");
        Timer t = new Timer(1500, e -> {
            Pin.closeAll();
            cfg.save();
            Log.write("==== 退出 ====");
            System.exit(0);
        });
        t.setRepeats(false);
        t.start();
    }
}
