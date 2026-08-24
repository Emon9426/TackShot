package tackshot.ai;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.datatransfer.StringSelection;
import java.awt.Toolkit;

/**
 * AI 结果浮窗（FR-8.4 · D1 停靠贴图旁）：加载流式 / 完成 / 错误 / 未配置 四类状态。
 * 深色自绘风格与贴图悬浮菜单同族（#1B212C 底 + 蓝 #2563EB 强调）；复制为显式按钮（V2.0.3 规则）。
 */
final class AiResultWindow {
    private static final Color BG = new Color(0x1B, 0x21, 0x2C);
    private static final Color TITLE_BG = new Color(0x22, 0x2B, 0x39);
    private static final Color LINE = new Color(0x32, 0x3D, 0x4D);
    private static final Color BTN = new Color(0x33, 0x41, 0x55);
    private static final Color BTN_LINE = new Color(0x46, 0x55, 0x6C);
    private static final Color ACCENT = new Color(0x25, 0x63, 0xEB);
    private static final Color TXT = new Color(0xF1, 0xF5, 0xF9);
    private static final Color TXT_DIM = new Color(0x94, 0xA3, 0xB8);
    private static final Color TXT_ORI = new Color(0x8F, 0xA0, 0xB8);

    private final JFrame frame = new JFrame();
    private final JLabel titleLabel = new JLabel("", SwingConstants.LEFT);
    private final JTextPane pane = new JTextPane(new DefaultStyledDocument());
    private final JPanel foot = new JPanel();
    private final JLabel status = new JLabel(" ");
    private float scale = 1f;

    private final String feature;
    private final String target;
    private String tier;
    private BufferedImageRef image;
    private String fullText = "";
    private final StringBuilder lineBuf = new StringBuilder();
    private boolean inOri = false;
    private Timer loadingAnim;
    private CopilotSdk.Handle handle;
    private long t0;

    /** 由 AiService 注入的动作。 */
    Runnable onRetry;
    java.util.function.BiConsumer<String, BufferedImageRef> onSwitchTier;
    Runnable onCancel;

    interface BufferedImageRef {
        java.awt.image.BufferedImage get();
    }

    AiResultWindow(String feature, String target, String tier, boolean topmost) {
        this.feature = feature;
        this.target = target;
        this.tier = tier;
        frame.setUndecorated(true);
        frame.setAlwaysOnTop(topmost);
        frame.setBackground(BG);
        frame.setType(java.awt.Window.Type.UTILITY);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close();
            }
        });
        buildUi();
        // Esc 关闭（取消进行中的请求，D5）
        javax.swing.KeyStroke esc = javax.swing.KeyStroke.getKeyStroke("ESCAPE");
        frame.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(esc, "aiClose");
        frame.getRootPane().getActionMap().put("aiClose", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                close();
            }
        });
    }

    private void buildUi() {
        scale = Math.max(1f, java.awt.Toolkit.getDefaultToolkit().getScreenResolution() / 96f);
        Font fT = new Font(tackshot.Main.UI_FONT_FAMILY, Font.BOLD, (int) (12.5f * scale + 0.5f));
        Font f = new Font(tackshot.Main.UI_FONT_FAMILY, Font.PLAIN, (int) (12.5f * scale + 0.5f));
        Font fBtn = new Font(tackshot.Main.UI_FONT_FAMILY, Font.PLAIN, (int) (12f * scale + 0.5f));
        Font fS = new Font(tackshot.Main.UI_FONT_FAMILY, Font.PLAIN, (int) (11f * scale + 0.5f));

        // 标题栏（可拖动）
        titleLabel.setFont(fT);
        titleLabel.setForeground(TXT);
        titleLabel.setOpaque(true);
        titleLabel.setBackground(TITLE_BG);
        titleLabel.setBorder(BorderFactory.createEmptyBorder((int) (7 * scale), (int) (12 * scale),
                (int) (7 * scale), (int) (12 * scale)));
        JLabel x = new JLabel("  ✕  ");
        x.setFont(fT);
        x.setForeground(TXT_DIM);
        x.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        x.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                close();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                x.setForeground(TXT);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                x.setForeground(TXT_DIM);
            }
        });
        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.setBackground(TITLE_BG);
        titleBar.add(titleLabel, BorderLayout.CENTER);
        titleBar.add(x, BorderLayout.EAST);
        Drag.install(titleBar, frame);

        // 内容区
        pane.setEditable(false);
        pane.setBackground(BG);
        pane.setForeground(TXT);
        pane.setFont(f);
        pane.setCaretColor(TXT);
        pane.setSelectionColor(new Color(0x25, 0x63, 0xEB, 160));
        pane.setBorder(BorderFactory.createEmptyBorder((int) (10 * scale), (int) (12 * scale),
                (int) (10 * scale), (int) (12 * scale)));
        JScrollPane sp = new JScrollPane(pane);
        sp.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, LINE));
        sp.getViewport().setBackground(BG);
        sp.setOpaque(true);
        sp.getVerticalScrollBar().setUnitIncrement(18);

        // 底部按钮区
        foot.setBackground(TITLE_BG);
        foot.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, (int) (6 * scale), (int) (6 * scale)));
        status.setFont(fS);
        status.setForeground(TXT_DIM);

        frame.getContentPane().setBackground(BG);
        frame.add(titleBar, BorderLayout.NORTH);
        frame.add(sp, BorderLayout.CENTER);
        frame.add(foot, BorderLayout.SOUTH);
    }

    // ---------------- 定位（D1：停靠锚点旁） ----------------

    void dock(Rectangle anchor) {
        Rectangle scr = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        if (anchor != null)
            for (var gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
                if (gd.getDefaultConfiguration().getBounds().intersects(anchor)) {
                    scr = gd.getDefaultConfiguration().getBounds();
                    break;
                }
        // 固定初始尺寸（内容区滚动）：pack() 此时内容未填充，会得到过窄的首选宽度
        int w = (int) Math.min(420 * scale, scr.width * 0.45);
        int h = (int) Math.min(360 * scale, scr.height * 0.55);
        int x, y;
        if (anchor != null) {
            x = anchor.x + anchor.width + (int) (12 * scale);
            y = anchor.y;
            if (x + w > scr.x + scr.width - 8) x = anchor.x - w - (int) (12 * scale);   // 放不下→左侧
            if (x < scr.x + 8) x = scr.x + scr.width - w - 8;
            if (y + h > scr.y + scr.height - 8) y = scr.y + scr.height - h - 8;
            if (y < scr.y + 8) y = scr.y + 8;
        } else {
            x = scr.x + scr.width - w - (int) (24 * scale);
            y = scr.y + (int) (90 * scale);
        }
        frame.setBounds(x, y, w, h);
    }

    void showWin() {
        frame.setVisible(true);
        frame.toFront();
    }

    boolean isVisible() {
        return frame.isShowing();
    }

    void close() {
        SwingUtilities.invokeLater(() -> {
            if (loadingAnim != null) loadingAnim.stop();
            if (onCancel != null) onCancel.run();
            frame.dispose();
        });
    }

    // ---------------- 状态渲染（线程安全） ----------------

    void showLoading() {
        SwingUtilities.invokeLater(() -> {
            t0 = System.currentTimeMillis();
            titleLabel.setText("✨ " + PromptLib.featureTitle(feature, target)
                    + "   " + CopilotSdk.tierLabel(tier));
            pane.setText("");
            fullText = "";
            lineBuf.setLength(0);
            appendStyled("正在识别…", TXT_DIM, false);
            foot.removeAll();
            foot.add(btn("取消 (Esc)", () -> close(), false));
            foot.add(status);
            status.setText(" ");
            foot.revalidate();
            foot.repaint();
            loadingAnim = new Timer(500, e -> {
                long el = (System.currentTimeMillis() - t0) / 1000;
                status.setText("已等待 " + el + " 秒");
            });
            loadingAnim.start();
        });
    }

    /** 流式追加（SDK 线程调用）。翻译模式按「原/译」行前缀着色。 */
    void appendDelta(String delta) {
        SwingUtilities.invokeLater(() -> {
            if (loadingAnim != null) loadingAnim.stop();
            try {
                DefaultStyledDocument doc = (DefaultStyledDocument) pane.getDocument();
                if (pane.getText().startsWith("正在识别")) {   // 清掉占位文本
                    doc.remove(0, doc.getLength());
                    lineBuf.setLength(0);
                }
                lineBuf.append(delta);
                int nl;
                while ((nl = lineBuf.indexOf("\n")) >= 0) {
                    String line = lineBuf.substring(0, nl);
                    lineBuf.delete(0, nl + 1);
                    emitLine(line, true);
                }
                if (lineBuf.length() > 0) {
                    String partial = lineBuf.toString();
                    lineBuf.setLength(0);
                    emitLine(partial, false);   // 未收到换行的行尾片段：按当前样式输出
                }
                pane.setCaretPosition(doc.getLength());
            } catch (Throwable ignored) {
            }
        });
    }

    private void emitLine(String line, boolean withNewline) {
        boolean ori = line.startsWith("原 ");
        boolean tra = line.startsWith("译 ");
        if (ori) inOri = true;
        else if (tra) inOri = false;
        Color c = PromptLib.FEAT_TRANSLATE.equals(feature) ? (ori || (!tra && inOri) ? TXT_ORI : TXT) : TXT;
        appendStyled(line + (withNewline ? "\n" : ""), c, false);
    }

    private void appendStyled(String s, Color c, boolean bold) {
        try {
            DefaultStyledDocument doc = (DefaultStyledDocument) pane.getDocument();
            SimpleAttributeSet a = new SimpleAttributeSet();
            StyleConstants.setForeground(a, c);
            StyleConstants.setBold(a, bold);
            StyleConstants.setFontFamily(a, tackshot.Main.UI_FONT_FAMILY);
            StyleConstants.setFontSize(a, (int) (12.5f * scale + 0.5f));
            doc.insertString(doc.getLength(), s, a);
        } catch (BadLocationException ignored) {
        }
    }

    void showDone(String full, long elapsedMs) {
        SwingUtilities.invokeLater(() -> {
            if (loadingAnim != null) loadingAnim.stop();
            fullText = full;
            boolean translate = PromptLib.FEAT_TRANSLATE.equals(feature);
            foot.removeAll();
            if (translate) {
                foot.add(btn("⧉ 复制译文", () -> copyText(extractLines(false)), true));
                foot.add(btn("⧉ 复制对照", () -> copyText(fullText), false));
            } else {
                foot.add(btn("⧉ 复制", () -> copyText(fullText), true));
            }
            foot.add(btn("↻ 重试", () -> {
                if (onRetry != null) onRetry.run();
            }, false));
            foot.add(menuBtn("⇄ 换模型 ▾"));
            foot.add(status);
            int chars = full.replace("\n", "").length();
            status.setText("完成 · " + chars + " 字 · " + String.format("%.1f", elapsedMs / 1000f) + " 秒");
            foot.revalidate();
            foot.repaint();
        });
    }

    void showError(String msg) {
        SwingUtilities.invokeLater(() -> {
            if (loadingAnim != null) loadingAnim.stop();
            titleLabel.setText("✨ " + PromptLib.featureTitle(feature, target));
            pane.setText("");
            appendStyled("⚠️ " + msg + "\n\n", TXT, true);
            appendStyled("可重试，或到设置中检查令牌/网络。", TXT_DIM, false);
            foot.removeAll();
            foot.add(btn("↻ 重试", () -> {
                if (onRetry != null) onRetry.run();
            }, true));
            foot.add(btn("打开设置…", AiResultWindow::openSettings, false));
            foot.add(status);
            status.setText(" ");
            foot.revalidate();
            foot.repaint();
        });
    }

    void showUnconfigured() {
        SwingUtilities.invokeLater(() -> {
            titleLabel.setText("✨ " + PromptLib.featureTitle(feature, target));
            pane.setText("");
            appendStyled("🔑 尚未配置 AI\n\n", TXT, true);
            appendStyled("粘贴 GitHub 访问令牌（细粒度 PAT，github_pat_ 开头）即可启用，\n"
                    + "使用你自己的 GitHub Copilot 订阅额度。\n\n"
                    + "获取路径：github.com → Settings → Developer settings →\n"
                    + "Personal access tokens → Fine-grained tokens\n", TXT_DIM, false);
            foot.removeAll();
            foot.add(btn("打开设置…", AiResultWindow::openSettings, true));
            foot.add(btn("关闭", () -> close(), false));
            foot.add(status);
            status.setText(" ");
            foot.revalidate();
            foot.repaint();
        });
    }

    // ---------------- 控件 ----------------

    private JButton btn(String text, Runnable act, boolean primary) {
        JButton b = new JButton(text);
        b.setFont(new Font(tackshot.Main.UI_FONT_FAMILY, Font.PLAIN, (int) (12f * scale + 0.5f)));
        b.setFocusPainted(false);
        if (primary) {
            b.setBackground(ACCENT);
            b.setForeground(Color.WHITE);
        } else {
            b.setBackground(BTN);
            b.setForeground(TXT);
        }
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(primary ? ACCENT : BTN_LINE),
                BorderFactory.createEmptyBorder(3, 12, 3, 12)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> act.run());
        return b;
    }

    private JButton menuBtn(String text) {
        JButton b = btn(text, () -> {
        }, false);
        JPopupMenu m = new JPopupMenu();
        Font mf = new Font(tackshot.Main.UI_FONT_FAMILY, Font.PLAIN, (int) (12f * scale + 0.5f));
        for (String t : new String[]{"fast", "balanced", "deep"}) {
            javax.swing.JMenuItem mi = new javax.swing.JMenuItem((t.equals(tier) ? "✓ " : "　") + CopilotSdk.tierLabel(t));
            mi.setFont(mf);
            mi.addActionListener(e -> {
                if (onSwitchTier != null) onSwitchTier.accept(t, image);
            });
            m.add(mi);
        }
        b.removeActionListener(b.getActionListeners()[0]);
        b.addActionListener(e -> m.show(b, 0, b.getHeight() + 2));
        return b;
    }

    /** 复制为显式动作（V2.0.3 规则：唯一写剪贴板路径）。 */
    private void copyText(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        status.setText("✓ 已复制到剪贴板");
    }

    private String extractLines(boolean original) {
        StringBuilder sb = new StringBuilder();
        for (String line : fullText.split("\n")) {
            if (original && line.startsWith("原 ")) sb.append(line.substring(2)).append('\n');
            if (!original && line.startsWith("译 ")) sb.append(line.substring(2)).append('\n');
        }
        return sb.toString().trim();
    }

    static void openSettings() {
        SwingUtilities.invokeLater(() -> tackshot.Settings.showDialog());
    }

    void setHandle(CopilotSdk.Handle h) {
        this.handle = h;
    }

    void setImage(BufferedImageRef img) {
        this.image = img;
    }

    void setTier(String tier) {
        this.tier = tier;
    }

    // ---------------- 标题栏拖动 ----------------

    private static final class Drag extends MouseAdapter {
        private final JFrame f;
        private java.awt.Point origin;

        private Drag(JFrame f) { this.f = f; }

        static void install(JPanel bar, JFrame f) {
            Drag d = new Drag(f);
            bar.addMouseListener(d);
            bar.addMouseMotionListener(d);
        }

        @Override
        public void mousePressed(MouseEvent e) {
            origin = e.getLocationOnScreen();
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (origin == null) return;
            java.awt.Point now = e.getLocationOnScreen();
            f.setLocation(f.getX() + now.x - origin.x, f.getY() + now.y - origin.y);
            origin = now;
        }
    }
}
