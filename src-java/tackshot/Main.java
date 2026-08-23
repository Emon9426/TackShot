package tackshot;

import javax.swing.Timer;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.AWTException;
import java.awt.CheckboxMenuItem;
import java.awt.Font;
import java.awt.Frame;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.HashMap;
import java.util.Map;

/** 钉图 TackShot V2.0（Java 版）入口：单实例 / 托盘 / 热键 / 输出分发 / 冒烟测试。 */
public final class Main {
    public static final String VERSION = "钉图 TackShot v2.0";
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

    /** 物理像素模型下 Swing 对话框字体按主屏 DPI 放大（自绘 UI 不受影响）。 */
    private static void scaleDialogFonts() {
        try {
            float s = Toolkit.getDefaultToolkit().getScreenResolution() / 96f;
            if (s <= 1.01f) return;
            Map<Object, Object> scaled = new HashMap<>();
            for (Object k : UIManager.getLookAndFeelDefaults().keySet().toArray()) {
                Object v = UIManager.getLookAndFeelDefaults().get(k);
                if (v instanceof Font)
                    scaled.put(k, ((Font) v).deriveFont(((Font) v).getSize2D() * s));
            }
            for (Map.Entry<Object, Object> en : scaled.entrySet())
                UIManager.put(en.getKey(), en.getValue());
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 托盘 ----------------

    private static void setupTray() {
        if (!SystemTray.isSupported()) {
            Log.write("警告：系统不支持托盘");
            return;
        }
        PopupMenu pm = new PopupMenu();
        MenuItem mRegion = new MenuItem("区域截图\tCtrl+Alt+A");
        mRegion.addActionListener(e -> Capture.startRegion());
        MenuItem mFull = new MenuItem("全屏截图\tCtrl+Alt+F");
        mFull.addActionListener(e -> Capture.startFullscreen());
        MenuItem mPin = new MenuItem("贴图（剪贴板）\tCtrl+Alt+P");
        mPin.addActionListener(e -> pinFromClipboard());
        pm.add(mRegion);
        pm.add(mFull);
        pm.add(mPin);
        pm.addSeparator();
        MenuItem mSettings = new MenuItem("设置…（M3 提供）");
        mSettings.setEnabled(false);
        pm.add(mSettings);
        CheckboxMenuItem mAuto = new CheckboxMenuItem("开机自启动", Nat.autoRunEnabled());
        mAuto.addItemListener(e -> Nat.setAutoRun(mAuto.getState()));
        pm.add(mAuto);
        pm.addSeparator();
        MenuItem mAbout = new MenuItem("关于 钉图 TackShot");
        mAbout.addActionListener(e -> JOptionPane.showMessageDialog(hidden,
                VERSION + "（Java 版）\n轻量级开源截图 · 贴图工具\n\n"
                        + "许可证：MIT（见 LICENSE 与 THIRD-PARTY-NOTICES）\n"
                        + "默认热键：Ctrl+Alt+A 区域 / Ctrl+Alt+F 全屏 / Ctrl+Alt+P 贴图\n\n"
                        + "本软件完全离线运行，不收集任何数据。",
                "关于", JOptionPane.INFORMATION_MESSAGE));
        pm.add(mAbout);
        MenuItem mExit = new MenuItem("退出");
        mExit.addActionListener(e -> quit());
        pm.add(mExit);
        try {
            tray = new TrayIcon(Icon.appIcon(), "钉图 TackShot v2.0 · Ctrl+Alt+A 截图", pm);
            tray.setImageAutoSize(true);
            tray.addActionListener(e -> Capture.startRegion());
            SystemTray.getSystemTray().add(tray);
        } catch (AWTException e) {
            Log.write("警告：托盘图标创建失败");
        }
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

    static void finishImage(BufferedImage img) {
        if (img == null) return;
        boolean copied = Clip.toClipboard(hidden, img);
        String msg;
        if (cfg.confirmAction.equals("copy_pin")) {
            Pin.create(img);
            msg = copied ? "已复制到剪贴板 · 已贴图" : "已贴图（复制到剪贴板失败）";
        } else if (cfg.confirmAction.equals("copy_save")) {
            String path = saveAuto(img);
            msg = path.isEmpty() ? "已复制 · 自动保存失败" : "已复制到剪贴板 · 已保存 " + path;
        } else {
            msg = copied ? "已复制到剪贴板" : "复制到剪贴板失败";
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
        if (Clip.toClipboard(null, bmp) && Nat.U32.I.IsClipboardFormatAvailable(2)) {
            Log.write("TEST PASS: 剪贴板写入");
            pass++;
        } else Log.write("TEST FAIL: 剪贴板写入");

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
