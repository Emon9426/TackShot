package tackshot;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** 贴图窗口：逐像素半透明置顶窗 / 缩放 / 透明度 / 悬浮菜单 / 就地编辑（对应 pin.cpp）。 */
final class Pin {
    static final List<Pin> pins = new ArrayList<>();
    private static final int BORDER = 2, STRIP_GAP = 6;
    private static final int T_MAX = 95;    // 上限 95%：永不 100% 全透明

    BufferedImage img;
    private int iw, ih;
    private double zoomX = 1.0, zoomY = 1.0;
    private int alpha = 255;
    private java.awt.Window wnd;
    private View view;
    private int ww, wh, topZone;
    private int imgX;                 // 图片内容在窗口内的水平居中偏移（窗口按菜单最小宽加宽时 >0）

    private boolean menuVisible = false;
    private boolean editing = false;
    private boolean shapeDrag = false;
    private boolean dead = false;
    private boolean topmost = true;       // 置顶开关（TB_TOP）：开=始终最前，关=允许被其他窗口遮挡

    private final Tb.Toolbar menu = new Tb.Toolbar();
    private final Tb.Toolbar bar = new Tb.Toolbar();
    private final Tb.MosaicFlyout flyout = new Tb.MosaicFlyout();
    private final AlphaFlyout alphaFly = new AlphaFlyout();
    private long sizeHudUntil, alphaHudUntil;
    private final Edit.Editor ed = new Edit.Editor();
    private int hover;
    private long hoverSince;

    private int resizing = -1;
    private int rsL, rsT, rsR, rsB;
    private Point dragWinOrigin, dragStart;   // 手动拖动（等价 HTCAPTION）
    // 选择工具对象编辑状态（FR-3.10）：坐标为贴图图像坐标
    private boolean objDrag, objDirty;
    private int objHandle = -1;
    private Point objStart;
    private Rectangle objB0;
    private Timer hoverT, hideT, tipT, animT, hudT;
    private final Cursor cross = Icon.crossCursor();

    private Pin() {}

    static int transPct(int a) { return ((255 - a) * 100 + 127) / 255; }

    static int alphaOfT(int t) {
        t = Math.max(0, Math.min(T_MAX, t));
        return 255 - t * 255 / 100;
    }

    static int topZonePx(float sc) {
        return (int) ((24 + STRIP_GAP) * sc + 0.5f);
    }

    static void create(BufferedImage src) {
        if (src == null || src.getWidth() < 1 || src.getHeight() < 1) return;
        Pin p = new Pin();
        p.iw = src.getWidth();
        p.ih = src.getHeight();
        p.img = new BufferedImage(p.iw, p.ih, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = p.img.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();

        Rectangle avail = Nat.workArea();
        double fit = Math.min(1.0, Math.min(avail.width * 0.8 / p.iw, avail.height * 0.8 / p.ih));
        p.zoomX = p.zoomY = fit;
        float sc0 = Nat.dpiScale(null);
        p.topZone = topZonePx(sc0);
        int ww = Math.max(p.imgW(), Tb.pinMinWinW(false, sc0));   // 悬浮菜单完整显示的最小宽
        int wh = p.imgH() + p.topZone;
        Point cp = MouseInfo.getPointerInfo().getLocation();
        int x = cp.x - ww / 2;
        int y = cp.y - (wh - p.topZone) / 2;
        x = Math.max(avail.x, Math.min(x, avail.x + avail.width - ww));
        y = Math.max(avail.y, Math.min(y, avail.y + avail.height - wh));

        javax.swing.JFrame f = new javax.swing.JFrame();
        f.setUndecorated(true);
        f.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        p.view = p.new View();
        p.view.setOpaque(false);
        p.view.setFocusable(true);
        f.setContentPane(p.view);
        f.setBounds(x, y, ww, wh);
        p.wire();
        p.initTimers();
        p.wnd = f;
        p.wnd.setAlwaysOnTop(true);
        p.wnd.setBackground(new Color(0, 0, 0, 0));   // 逐像素半透明
        p.wnd.setAutoRequestFocus(false);             // SW_SHOWNOACTIVATE
        p.wnd.setFocusableWindowState(true);
        p.render();
        p.wnd.setVisible(true);
        Nat.hideFromTaskbar(p.wnd);
        Nat.makeActivatable(p.wnd);
        pins.add(p);
        Log.write("贴图创建：image " + p.iw + "x" + p.ih + "，当前贴图 " + pins.size() + " 个");
    }

    static int count() {
        return pins.size();
    }

    boolean isShown() {
        return wnd != null && wnd.isShowing();
    }

    static void closeAll() {
        for (Pin p : new ArrayList<>(pins)) p.close();
        pins.clear();
    }

    private void close() {
        if (dead || wnd == null) return;
        if (TextInput.active()) TextInput.cancel();
        if (alphaEditPin == this) closeAlphaEdit();
        stopTimers();
        dead = true;
        wnd.dispose();
        wnd = null;
        pins.remove(this);
    }

    private void stopTimers() {
        if (hoverT != null) hoverT.stop();
        if (hideT != null) hideT.stop();
        if (tipT != null) tipT.stop();
        if (animT != null) animT.stop();
        if (hudT != null) hudT.stop();
    }

    private void initTimers() {
        hoverT = new Timer(330, e -> {
            Point cp = MouseInfo.getPointerInfo().getLocation();
            Point wl = wnd.getLocationOnScreen();
            int lx = cp.x - wl.x, ly = cp.y - wl.y;
            if (lx >= 0 && lx < ww && ly >= topZone && ly < wh && !editing && !menuVisible) {
                menuVisible = true;
                render();
            }
        });
        hoverT.setRepeats(false);
        hideT = new Timer(500, e -> {
            if (!editing && !alphaFly.visible) {
                menuVisible = false;
                render();
            }
        });
        hideT.setRepeats(false);
        tipT = new Timer(320, e -> {
            if (hover != 0) render();
        });
        tipT.setRepeats(false);
        animT = new Timer(40, e -> {
            render();
            if (hover == 0 || hoverSince == 0 || Main.nowMs() - hoverSince > 260) animT.stop();
        });
        hudT = new Timer(90, e -> {
            render();
            if ((sizeHudUntil == 0 || Main.nowMs() > sizeHudUntil)
                    && (alphaHudUntil == 0 || Main.nowMs() > alphaHudUntil)) hudT.stop();
        });
    }

    private int imgW() { return Math.max(8, (int) (iw * zoomX) + 2 * BORDER); }

    private int imgH() { return Math.max(8, (int) (ih * zoomY) + 2 * BORDER); }

    private Point imgPt(int x, int y) {
        return new Point((int) ((x - imgX - BORDER) / zoomX), (int) ((y - topZone - BORDER) / zoomY));
    }

    void render() {
        if (wnd == null) return;
        float sc = Nat.dpiScale(wnd);
        topZone = topZonePx(sc);
        ww = Math.max(imgW(), Tb.pinMinWinW(editing, sc));   // 菜单任何情况下完整显示
        imgX = (ww - imgW()) / 2;
        wh = imgH() + topZone;
        if (wnd.getWidth() != ww || wnd.getHeight() != wh) wnd.setSize(ww, wh);
        if (view != null) view.repaint();
    }

    // ---------------- 渲染 ----------------

    private void frame(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        float sc = Nat.dpiScale(wnd);

        // 整体不透明度：以绘制端 alpha 合成实现（等价 SourceConstantAlpha × 逐像素 alpha）
        g.setComposite(AlphaComposite.SrcOver.derive(alpha / 255f));

        int cw = imgW() - 2 * BORDER, ch = imgH() - 2 * BORDER;
        g.drawImage(img, imgX + BORDER, topZone + BORDER, imgX + BORDER + cw, topZone + BORDER + ch,
                0, 0, iw, ih, null);
        g.setColor(editing ? new Color(59, 130, 246) : Color.BLACK);
        g.setStroke(new BasicStroke(2f));
        g.draw(new Rectangle2D.Float(imgX + 1f, topZone + 1f, imgW() - 2f, imgH() - 2f));

        if (editing) {
            AffineTransform save = g.getTransform();
            g.translate(imgX + BORDER, topZone + BORDER);
            g.scale((float) zoomX, (float) zoomY);
            Edit.drawShapes(g, ed.shapes, img, 0, 0);
            if (shapeDrag) Edit.drawShape(g, ed.draft, img, 0, 0);
            if (ed.selected != null && ed.cur == Edit.Tool.Select)
                Edit.drawSelection(g, ed.selected, (float) zoomX);
            g.setTransform(save);
        }

        Rectangle strip = new Rectangle(0, 0, ww, topZone);
        float hs = 1f;
        if (hover != 0 && hoverSince != 0) {
            double el = Main.nowMs() - hoverSince;
            float t = (float) Math.min(1.0, el / 160.0);
            hs = 1f + 0.30f * t * (2f - t);
        }
        if (editing) {
            bar.layout(strip, strip, Tb.MODE_PIN_EDIT, sc);
            bar.draw(g, ed, hover, Tb.MODE_PIN_EDIT, hs);
        } else if (menuVisible) {
            menu.zoomPct = (int) (zoomX * 100 + 0.5);
            menu.topmost = topmost;
            menu.layout(strip, strip, Tb.MODE_PIN_HOVER, sc);
            menu.draw(g, null, hover, Tb.MODE_PIN_HOVER, hs);
        }
        // 二级菜单展开期间抑制悬停提示条（避免与 ◐ 长提示文字叠影）
        if (hover != 0 && hoverSince != 0 && !alphaFly.visible
                && !(editing && flyout.visible) && Main.nowMs() - hoverSince > 300) {
            Tb.drawTooltip(g, mouseLocal(), new Rectangle(0, 0, ww, wh), Tb.name(hover), sc);
        }

        if (editing && flyout.visible) {
            for (Tb.TbBtn b : bar.btns)
                if (b.id == Tb.TB_MOSAIC) {
                    flyout.layout(b.r, new Rectangle(0, 0, ww, wh), sc);
                    break;
                }
            flyout.draw(g, ed, sc);
        }
        if (editing && ed.cur == Edit.Tool.Mosaic && sizeHudUntil != 0
                && Main.nowMs() < sizeHudUntil) {
            Tb.drawSizeHud(g, mouseLocal(), ed, sc);
        }
        if (!editing && menuVisible && alphaFly.visible)
            alphaFly.draw(g, transPct(alpha));
        if (alphaHudUntil != 0 && Main.nowMs() < alphaHudUntil)
            Tb.drawTextHud(g, mouseLocal(), "透明度 " + transPct(alpha) + "%", sc);
    }

    private Point mouseLocal() {
        Point cp = MouseInfo.getPointerInfo().getLocation();
        Point wl = wnd.getLocationOnScreen();
        return new Point(cp.x - wl.x, cp.y - wl.y);
    }

    // ---------------- 缩放 ----------------

    /** 等比缩放（滚轮 / 菜单按钮）：保持光标下的图像点不动。 */
    private void zoomAt(double factor, Point cursorScr) {
        double nz = Math.min(8.0, Math.max(0.1, zoomX * factor));
        zoomX = zoomY = nz;
        Point wl = wnd.getLocationOnScreen();
        render();
        Point anchor = imgPt(cursorScr.x - wl.x, cursorScr.y - wl.y);
        int nw = imgW(), nh = imgH() + topZone;
        int nx = cursorScr.x - (int) (anchor.x * nz) - BORDER - imgX;
        int ny = cursorScr.y - (int) (anchor.y * nz) - BORDER - topZone;
        wnd.setBounds(nx, ny, nw, nh);
        render();
    }

    /** 图片区（含边框，不含顶部悬浮区）的 8 向缩放命中区。 */
    private int resizeZone(int x, int y) {
        float sc = Nat.dpiScale(wnd);
        int hz = (int) Math.max(6.0, 6.0 * sc) + BORDER;
        int x0 = 0, x1 = ww, y0 = topZone, y1 = wh;
        boolean inY = y >= y0 && y <= y1;
        boolean inX = x >= x0 && x <= x1;
        boolean L = inY && x >= x0 && x < x0 + hz;
        boolean R = inY && x >= x1 - hz && x < x1;
        boolean T = inX && y >= y0 && y < y0 + hz;
        boolean B = inX && y >= y1 - hz && y < y1;
        if (L && T) return 0;
        if (R && T) return 2;
        if (R && B) return 4;
        if (L && B) return 6;
        if (T) return 1;
        if (B) return 5;
        if (L) return 7;
        if (R) return 3;
        return -1;
    }

    private void doResize(Point cpScr) {
        int code = resizing;
        boolean leftMv = code == 0 || code == 6 || code == 7;
        boolean rightMv = code == 2 || code == 3 || code == 4;
        boolean topMv = code == 0 || code == 1 || code == 2;
        boolean botMv = code == 4 || code == 5 || code == 6;
        double minZ = 0.1, maxZ = 8.0;
        double rx = zoomX, ry = zoomY;
        if (rightMv) rx = (cpScr.x - rsL - 2.0 * BORDER) / iw;
        if (leftMv) rx = (rsR - cpScr.x - 2.0 * BORDER) / iw;
        if (botMv) ry = (cpScr.y - rsT - topZone - 2.0 * BORDER) / ih;
        if (topMv) ry = (rsB - cpScr.y - topZone - 2.0 * BORDER) / ih;
        if (leftMv || rightMv) {
            if (topMv || botMv) {           // 四角：等比
                double z = Math.min(Math.max(Math.max(rx, ry), minZ), maxZ);
                zoomX = zoomY = z;
            } else {
                zoomX = Math.min(Math.max(rx, minZ), maxZ);
            }
        } else if (topMv || botMv) {
            zoomY = Math.min(Math.max(ry, minZ), maxZ);
        }
        int nw = imgW(), nh = imgH() + topZone;
        int nx = leftMv ? rsR - nw : rsL;
        int ny = topMv ? rsB - nh : rsT;
        wnd.setBounds(nx, ny, nw, nh);
        render();
    }

    // ---------------- 透明度（FR-4.13） ----------------

    private void showAlphaHud() {
        alphaHudUntil = Main.nowMs() + 800;
        hudT.start();
    }

    private void cycleAlpha() {
        int t = transPct(alpha);
        int nt = t < 12 ? 25 : (t < 50 ? 75 : 0);
        alpha = alphaOfT(nt);
        showAlphaHud();
        Log.write("透明度循环：" + nt + "%");
        render();
    }

    // 透明度二级菜单：[ − ][ 75% ][ + ]
    static final class AlphaFlyout {
        boolean visible = false;
        Rectangle rc = new Rectangle();
        float scale = 1f;

        void layout(Rectangle btn, Rectangle clip, float sc) {
            scale = Math.max(1f, sc);
            int cw = (int) (34 * scale + 0.5f), ch = (int) (26 * scale + 0.5f);
            int x = btn.x + btn.width / 2 - cw;
            x = Math.max(2, Math.min(x, clip.x + clip.width - cw * 3 - 2));
            int y = btn.y + btn.height + (int) (5 * scale + 0.5f);
            rc = new Rectangle(x, y, cw * 3, ch);
        }

        int hit(int x, int y) {
            if (!visible || !Tb.ptIn(rc, x, y)) return 0;
            return 1 + Math.min(2, Math.max(0, (x - rc.x) * 3 / Math.max(1, rc.width)));
        }

        void hide() { visible = false; }

        void draw(Graphics2D g, int tPct) {
            int w = rc.width, h = rc.height, cw = w / 3;
            g.setColor(new Color(30, 37, 48, 243));
            g.fill(new Rectangle2D.Float(rc.x, rc.y, w, h));
            g.setColor(new Color(51, 65, 85, 70));
            g.fill(new Rectangle2D.Float(rc.x + cw, rc.y, cw, h));
            g.setColor(new Color(51, 65, 85, 255));
            g.setStroke(new BasicStroke(1f));
            g.draw(new Rectangle2D.Float(rc.x, rc.y, w - 1, h - 1));
            g.setFont(Tb.uiFont(11f * scale, Font.BOLD));
            g.setColor(new Color(241, 245, 249));
            FontMetrics fm = g.getFontMetrics();
            String mid = tPct + "%";
            drawCentered(g, "−", rc.x, rc.y, cw, h, fm);
            drawCentered(g, mid, rc.x + cw, rc.y, cw, h, fm);
            drawCentered(g, "+", rc.x + 2 * cw, rc.y, w - 2 * cw, h, fm);
        }

        private static void drawCentered(Graphics2D g, String s, int x, int y, int w, int h, FontMetrics fm) {
            float tw = (float) fm.getStringBounds(s, g).getWidth();
            g.drawString(s, x + (w - tw) / 2f, y + (h - fm.getHeight()) / 2f + fm.getAscent());
        }
    }

    // 透明度百分比输入弹窗（与 C++ 一致：Enter 应用、Esc/失焦取消）
    private static JDialog alphaDlg;
    private static JTextField alphaFld;
    private static Pin alphaEditPin;

    private static void closeAlphaEdit() {
        if (alphaDlg != null) {
            JDialog d = alphaDlg;
            alphaDlg = null;
            alphaEditPin = null;
            d.dispose();
        }
    }

    private static void applyAlphaEdit() {
        if (alphaDlg == null || alphaEditPin == null) {
            closeAlphaEdit();
            return;
        }
        Pin p = alphaEditPin;
        int v;
        try {
            v = Integer.parseInt(alphaFld.getText().trim());
        } catch (NumberFormatException ex) {
            v = 0;
        }
        closeAlphaEdit();
        if (p.dead || p.wnd == null) return;
        int t = Math.max(0, Math.min(T_MAX, v));
        p.alpha = alphaOfT(t);
        p.showAlphaHud();
        if (v != t) Log.write("透明度输入：" + v + " → 夹紧 " + t + "%（上限 95%）");
        else Log.write("透明度输入：" + t + "%");
        p.render();
    }

    private void openAlphaEdit() {
        closeAlphaEdit();
        Point wl = wnd.getLocationOnScreen();
        float sc = alphaFly.scale;
        int w = (int) (46 * sc + 0.5f), h = (int) (26 * sc + 0.5f);
        int fw = alphaFly.rc.width, cw = fw / 3;
        int x = wl.x + alphaFly.rc.x + cw + (cw - w) / 2;
        int y = wl.y + alphaFly.rc.y - (h - alphaFly.rc.height) / 2;
        alphaEditPin = this;
        alphaDlg = new JDialog(Main.hidden, false);
        alphaDlg.setUndecorated(true);
        alphaDlg.setAlwaysOnTop(true);
        alphaFld = new JTextField(String.valueOf(transPct(alpha)));
        alphaFld.setFont(new Font(Tb.UI_FAMILY, Font.BOLD, (int) (13 * sc + 0.5f)));
        alphaFld.setHorizontalAlignment(JTextField.CENTER);
        alphaFld.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(113, 113, 113)),
                BorderFactory.createEmptyBorder(0, 2, 0, 2)));
        ((AbstractDocument) alphaFld.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void insertString(FilterBypass fb, int offs, String str, AttributeSet a)
                    throws BadLocationException {
                if (!str.matches("\\d*")) return;
                if (fb.getDocument().getLength() + str.length() > 2) return;
                fb.insertString(offs, str, a);
            }

            @Override
            public void replace(FilterBypass fb, int offs, int len, String str, AttributeSet a)
                    throws BadLocationException {
                if (!str.matches("\\d*")) return;
                if (fb.getDocument().getLength() - len + str.length() > 2) return;
                fb.replace(offs, len, str, a);
            }
        });
        alphaFld.addActionListener(e -> applyAlphaEdit());
        alphaFld.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) closeAlphaEdit();
            }
        });
        alphaFld.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) { closeAlphaEdit(); }
        });
        alphaDlg.setContentPane(alphaFld);
        alphaDlg.setSize(w, h);
        alphaDlg.setLocation(x, y);
        alphaDlg.setVisible(true);
        Nat.hideFromTaskbar(alphaDlg);
        alphaFld.requestFocusInWindow();
        alphaFld.selectAll();
        Log.write("透明度输入框：当前 " + transPct(alpha) + "%");
    }

    // ---------------- 动作 ----------------

    /** 悬浮菜单/编辑工具条"保存"共用：自动保存 + 复制剪贴板 + 关闭贴图（V2.1 合并两条路径）。 */
    private void saveImageAndClose(BufferedImage image) {
        String dir = Main.cfg.outputDir.isEmpty() ? Out.defaultSaveDir() : Main.cfg.outputDir;
        boolean jpeg = Main.cfg.format.equals("jpeg");
        String path = Out.buildSavePath(dir, jpeg ? "jpg" : "png");
        boolean saved = Out.save(image, path, jpeg, Main.cfg.jpegQuality);
        boolean copied = Clip.toClipboard(wnd, image);
        String msg = saved
                ? (copied ? "已复制到剪贴板 · 已保存 " + path : "已保存 " + path)
                : (copied ? "已复制到剪贴板 · 自动保存失败" : "保存失败：无法写入目标文件");
        Main.balloon("钉图 TackShot", msg);
        Log.write("输出完成：" + msg);
        close();
    }

    /** 悬浮菜单"保存"：自动保存 + 复制剪贴板 + 关闭贴图（V2.0 交付语义）。 */
    private void savePinAndClose() {
        saveImageAndClose(img);
    }

    /** 当前贴图+编辑标注的合成图。 */
    private BufferedImage composeEdited() {
        BufferedImage out = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(img, 0, 0, iw, ih, null);
        Edit.drawShapes(g, ed.shapes, img, 0, 0);
        g.dispose();
        return out;
    }

    /** 就地编辑完成：合成新图（V2.0.3 起不写剪贴板，FR-4.11 已裁剪，复制走『复制』按钮）。 */
    private void applyEdit() {
        if (TextInput.active()) TextInput.cancel();
        img = composeEdited();
        Main.balloon("钉图 TackShot", "贴图已更新（复制请点悬浮菜单『复制』）");
        editing = false;
        shapeDrag = false;
        render();
    }

    private void onMenu(int id) {
        switch (id) {
            case Tb.TB_EDIT:
                editing = true;
                menuVisible = false;       // 硬不变量：编辑态下入口菜单必须隐藏
                hover = 0;
                hoverT.stop();
                hideT.stop();
                ed.reset();
                ed.cur = Edit.Tool.None;
                ed.widthIdx = 1;
                view.requestFocusInWindow();
                Log.write("进入贴图编辑（入口菜单强制隐藏）");
                break;
            case Tb.TB_COPYIMG:
                Clip.toClipboard(wnd, img);
                Main.balloon("钉图 TackShot", "已复制到剪贴板");
                Log.write("输出完成：已复制到剪贴板");
                close();
                return;
            case Tb.TB_SAVE:
                savePinAndClose();
                return;
            case Tb.TB_ZOOMIN: {
                Point wl = wnd.getLocationOnScreen();
                zoomAt(1.25, new Point(wl.x + ww / 2, wl.y + topZone + (wh - topZone) / 2));
                return;
            }
            case Tb.TB_ZOOMOUT: {
                Point wl = wnd.getLocationOnScreen();
                zoomAt(0.8, new Point(wl.x + ww / 2, wl.y + topZone + (wh - topZone) / 2));
                return;
            }
            case Tb.TB_OPAQUE:
                cycleAlpha();
                return;
            case Tb.TB_TOP:
                topmost = !topmost;
                wnd.setAlwaysOnTop(topmost);
                Log.write(topmost ? "贴图置顶：开（始终显示在最前）" : "贴图置顶：关（允许被其他窗口遮挡）");
                break;
            case Tb.TB_CLOSE:
                close();
                return;
            default:
                break;
        }
        render();
    }

    private void onEditBar(int id) {
        switch (id) {
            case Tb.TB_OK:
                applyEdit();
                return;
            case Tb.TB_COPYIMG: {          // 编辑态"复制"（V2.1）：输出含标注合成图并关闭贴图
                BufferedImage out = composeEdited();
                boolean copied = Clip.toClipboard(wnd, out);
                String msg = copied ? "已复制到剪贴板（含编辑）" : "复制到剪贴板失败";
                Main.balloon("钉图 TackShot", msg);
                Log.write("输出完成：" + msg);
                close();
                return;
            }
            case Tb.TB_SAVE:               // 编辑态"保存"（V2.1）：自动保存+复制并关闭贴图
                saveImageAndClose(composeEdited());
                return;
            case Tb.TB_CANCEL:
                editing = false;
                shapeDrag = false;
                ed.selected = null;
                break;
            case Tb.TB_SELECT: ed.cur = Edit.Tool.Select; break;
            case Tb.TB_RECT: ed.cur = Edit.Tool.Rect; break;
            case Tb.TB_ELLIPSE: ed.cur = Edit.Tool.Ellipse; break;
            case Tb.TB_LINE: ed.cur = Edit.Tool.Line; break;
            case Tb.TB_ARROW: ed.cur = Edit.Tool.Arrow; break;
            case Tb.TB_PEN: ed.cur = Edit.Tool.Pen; break;
            case Tb.TB_TEXT: ed.cur = Edit.Tool.Text; break;
            case Tb.TB_MOSAIC: ed.cur = Edit.Tool.Mosaic; break;
            case Tb.TB_HIGHLIGHT: ed.cur = Edit.Tool.Highlight; break;
            case Tb.TB_UNDO: ed.undo(); break;
            case Tb.TB_REDO: ed.redoOp(); break;
            case Tb.TB_C0: case Tb.TB_C1: case Tb.TB_C2:
            case Tb.TB_C3: case Tb.TB_C4: case Tb.TB_C5:
                ed.color = Tb.PALETTE[id - Tb.TB_C0];
                Edit.applyColor(ed, ed.color);
                break;
            case Tb.TB_W0: ed.widthIdx = 0; Edit.applyWidth(ed, 0); break;
            case Tb.TB_W1: ed.widthIdx = 1; Edit.applyWidth(ed, 1); break;
            case Tb.TB_W2: ed.widthIdx = 2; Edit.applyWidth(ed, 2); break;
            default: break;
        }
        if (ed.cur != Edit.Tool.Select) ed.selected = null;
        render();
    }

    // ---------------- 事件 ----------------

    private void wire() {
        view.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                Pin.this.onPressed(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                Pin.this.onReleased(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2 && !editing && resizeZone(e.getX(), e.getY()) < 0) close();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoverT.stop();
                if (menuVisible && !editing) hideT.restart();
            }
        });
        view.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                Pin.this.onMove(e.getX(), e.getY());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                Pin.this.onDrag(e.getX(), e.getY());
            }
        });
        view.addMouseWheelListener(this::onWheel);
        view.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                Pin.this.onKey(e);
            }
        });
    }

    private void onMove(int x, int y) {
        if (wnd == null) return;
        hideT.stop();
        int hov = editing ? bar.hit(x, y) : (menuVisible ? menu.hit(x, y) : 0);
        if (hov != hover) {
            hover = hov;
            hoverSince = Main.nowMs();
            tipT.stop();
            animT.stop();
            if (hov != 0) {
                tipT.start();
                animT.start();
            }
            render();
        }
        // 光标反馈：边缘 8 向缩放 / 图片区移动 / 编辑绘制十字
        int rz = resizeZone(x, y);
        Cursor hc = Cursor.getDefaultCursor();
        if (rz == 0 || rz == 4) hc = Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR);
        else if (rz == 2 || rz == 6) hc = Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR);
        else if (rz == 1 || rz == 5) hc = Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR);
        else if (rz == 3 || rz == 7) hc = Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR);
        else if (editing && ed.cur == Edit.Tool.Select) {
            Point ip = imgPt(x, y);
            int hd = ed.selected != null
                    ? Edit.handleAt(ed.selected, ip.x, ip.y, (int) (9 / Math.max(0.05, zoomX)) + 2) : -1;
            if (hd == 8 || hd == 9) hc = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR);
            else if (hd >= 0) hc = cursorForHandle(hd);
            else if (Edit.hitShape(ed.shapes, ip.x, ip.y) >= 0)
                hc = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR);
        } else if (editing && ed.cur != Edit.Tool.None) hc = cross;
        else if (y > topZone) hc = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR);
        view.setCursor(hc);
        if (!menuVisible && !editing && y > topZone) hoverT.restart();
    }

    private static Cursor cursorForHandle(int hd) {
        switch (hd) {
            case 0: case 4: return Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR);
            case 2: case 6: return Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR);
            case 1: case 5: return Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR);
            default: return Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR);
        }
    }

    /** 选择工具拖动（FR-3.10）：移动整体或拖控制点；首次产生修改时才快照（懒撤销）。 */
    private void dragObject(Point cp) {
        if (ed.selected == null) return;
        int dx = cp.x - objStart.x, dy = cp.y - objStart.y;
        if (objDrag) {
            if (!objDirty && (dx != 0 || dy != 0)) {
                ed.beginOp();
                objDirty = true;
            }
            if (objDirty) Edit.translate(ed.selected, dx, dy);
            objStart = cp;
        } else if (objHandle >= 0) {
            if (!objDirty) {
                ed.beginOp();
                objDirty = true;
            }
            Edit.applyHandle(ed.selected, objHandle, objB0, cp.x, cp.y);
        }
    }

    private void onDrag(int x, int y) {
        if (wnd == null) return;
        if (objDrag || objHandle >= 0) {   // 选择工具对象拖动/缩放（FR-3.10）
            dragObject(imgPt(x, y));
            render();
            return;
        }
        if (resizing >= 0) {
            doResize(MouseInfo.getPointerInfo().getLocation());
            return;
        }
        if (shapeDrag) {
            Point ip = imgPt(x, y);
            if (ed.cur == Edit.Tool.Pen) {
                if (ed.draft.pts == null) ed.draft.pts = new ArrayList<>();
                ed.draft.pts.add(new int[]{ip.x, ip.y});
            } else {
                ed.draft.bx = ip.x;
                ed.draft.by = ip.y;
            }
            render();
            return;
        }
        if (dragWinOrigin != null) {
            Point cp = MouseInfo.getPointerInfo().getLocation();
            wnd.setLocation(dragWinOrigin.x + cp.x - dragStart.x, dragWinOrigin.y + cp.y - dragStart.y);
        }
    }

    private void onPressed(MouseEvent e) {
        if (wnd == null) return;
        view.requestFocusInWindow();
        int x = e.getX(), y = e.getY();
        if (e.getButton() == MouseEvent.BUTTON3) {
            onRightDown(x, y);
            return;
        }
        if (e.getButton() != MouseEvent.BUTTON1) return;
        if (!editing && menuVisible) {
            if (alphaFly.visible) {
                int h = alphaFly.hit(x, y);
                if (h == 1 || h == 3) {
                    int t = transPct(alpha) + (h == 3 ? 10 : -10);
                    alpha = alphaOfT(t);
                    showAlphaHud();
                    Log.write("透明度步进：" + transPct(alpha) + "%");
                    render();
                    return;
                }
                if (h == 2) {
                    openAlphaEdit();
                    return;
                }
                alphaFly.hide();
                render();
            }
            int id = menu.hit(x, y);
            if (id != 0) {
                onMenu(id);
                return;
            }
        }
        if (editing) {
            if (flyout.visible) {
                int ms = flyout.hit(x, y);
                flyout.hide();
                if (ms != 0) {
                    ed.mosaicStyle = Tb.msToStyle(ms);
                    render();
                    return;
                }
            }
            int id = bar.hit(x, y);
            if (id == Tb.TB_MOSAIC) {
                Rectangle cz = Tb.mosaicCaretZone(bar);
                if (Tb.ptIn(cz, x, y)) {
                    for (Tb.TbBtn b : bar.btns)
                        if (b.id == Tb.TB_MOSAIC) {
                            flyout.layout(b.r, new Rectangle(0, 0, ww, wh), Nat.dpiScale(wnd));
                            break;
                        }
                    flyout.visible = true;
                    render();
                    return;
                }
                flyout.hide();
            }
            if (id != 0) {
                onEditBar(id);
                return;
            }
            if (x >= BORDER && y >= topZone + BORDER && x < ww - BORDER && y < wh - BORDER) {
                int rz = resizeZone(x, y);
                if (rz >= 0) {           // 编辑态也允许从边缘调整尺寸
                    Rectangle wr = wnd.getBounds();
                    rsL = wr.x;
                    rsT = wr.y;
                    rsR = wr.x + wr.width;
                    rsB = wr.y + wr.height;
                    resizing = rz;
                    return;
                }
                Point ip = imgPt(x, y);
                if (ed.cur == Edit.Tool.Select) {   // 选择工具（FR-3.10）
                    int tol = (int) (9 / Math.max(0.05, zoomX)) + 2;
                    if (ed.selected != null) {
                        int hd = Edit.handleAt(ed.selected, ip.x, ip.y, tol);
                        if (hd >= 0) {
                            objHandle = hd;
                            objStart = ip;
                            objB0 = Edit.bounds(ed.selected);
                            return;
                        }
                    }
                    int idx = Edit.hitShape(ed.shapes, ip.x, ip.y);
                    if (idx >= 0) {
                        ed.selected = ed.shapes.get(idx);
                        objDrag = true;
                        objStart = ip;
                        objB0 = Edit.bounds(ed.selected);
                    } else {
                        ed.selected = null;
                    }
                    render();
                    return;
                }
                if (ed.cur == Edit.Tool.Text) {
                    final int col = ed.color;
                    final int fs = Edit.fontSizeFor(ed.widthIdx);
                    final Point fip = ip;
                    Point sp = MouseInfo.getPointerInfo().getLocation();
                    TextInput.start(wnd, sp, fs, col, txt -> {
                        if (dead || wnd == null) return;
                        if (!txt.isEmpty()) {
                            Edit.Shape sh = new Edit.Shape();
                            sh.tool = Edit.Tool.Text;
                            sh.color = col;
                            sh.fontSize = fs;
                            sh.ax = fip.x;
                            sh.ay = fip.y;
                            sh.text = txt;
                            ed.push(sh);
                        }
                        render();
                    });
                    return;
                }
                if (ed.cur != Edit.Tool.None) {
                    shapeDrag = true;
                    ed.draft = new Edit.Shape();
                    ed.draft.tool = ed.cur;
                    ed.draft.color = ed.color;
                    ed.draft.penW = Edit.penWidth(ed.widthIdx);
                    ed.draft.fontSize = Edit.fontSizeFor(ed.widthIdx);
                    ed.draft.mStyle = ed.mosaicStyle;
                    ed.draft.mSize = ed.mosaicSize;
                    ed.draft.ax = ip.x;
                    ed.draft.ay = ip.y;
                    ed.draft.bx = ip.x;
                    ed.draft.by = ip.y;
                    if (ed.cur == Edit.Tool.Pen) {
                        ed.draft.pts = new ArrayList<>();
                        ed.draft.pts.add(new int[]{ip.x, ip.y});
                    }
                    render();
                    return;
                }
            }
        }
        int rz = resizeZone(x, y);
        if (rz >= 0) {
            Rectangle wr = wnd.getBounds();
            rsL = wr.x;
            rsT = wr.y;
            rsR = wr.x + wr.width;
            rsB = wr.y + wr.height;
            resizing = rz;
            return;
        }
        // 其余情况：拖动窗口
        dragStart = MouseInfo.getPointerInfo().getLocation();
        dragWinOrigin = wnd.getLocation();
    }

    private void onReleased(MouseEvent e) {
        objDrag = false;          // 对象拖动结束（纯点击未修改则不留撤销快照）
        objHandle = -1;
        objDirty = false;
        if (resizing >= 0) {
            resizing = -1;
            return;
        }
        if (shapeDrag) {
            shapeDrag = false;
            Edit.Shape d = ed.draft;
            boolean ok = d.tool == Edit.Tool.Pen
                    ? (d.pts != null && d.pts.size() > 1)
                    : (Math.abs(d.bx - d.ax) + Math.abs(d.by - d.ay) > 4);
            if (ok) ed.push(d);
            render();
        }
        dragWinOrigin = null;
    }

    private void onRightDown(int x, int y) {
        if (editing) {
            if (bar.hit(x, y) == Tb.TB_MOSAIC) {
                for (Tb.TbBtn b : bar.btns)
                    if (b.id == Tb.TB_MOSAIC) {
                        flyout.layout(b.r, new Rectangle(0, 0, ww, wh), Nat.dpiScale(wnd));
                        break;
                    }
                flyout.visible = !flyout.visible;
                render();
                return;
            }
            editing = false;
            shapeDrag = false;
            flyout.hide();
            render();
            return;
        }
        if (menuVisible && menu.hit(x, y) == Tb.TB_OPAQUE) {
            for (Tb.TbBtn b : menu.btns)
                if (b.id == Tb.TB_OPAQUE) {
                    alphaFly.layout(b.r, new Rectangle(0, 0, ww, wh), Nat.dpiScale(wnd));
                    break;
                }
            alphaFly.visible = !alphaFly.visible;
            if (!alphaFly.visible) closeAlphaEdit();
            Log.write(alphaFly.visible ? "透明度二级菜单：展开" : "透明度二级菜单：收起");
            render();
            return;
        }
        if (alphaFly.visible) {
            alphaFly.hide();
            closeAlphaEdit();
            render();
            return;
        }
        close();
    }

    private void onWheel(MouseWheelEvent e) {
        int delta = e.getWheelRotation();
        if (editing && ed.cur == Edit.Tool.Mosaic) {
            ed.mosaicSize = Math.min(80, Math.max(6, ed.mosaicSize + (delta > 0 ? -4 : 4)));
            sizeHudUntil = Main.nowMs() + 800;
            hudT.start();
            render();
            return;
        }
        Point cp = MouseInfo.getPointerInfo().getLocation();
        if (e.isControlDown()) {
            int t = transPct(alpha) + (delta > 0 ? -10 : 10);
            alpha = alphaOfT(t);
            showAlphaHud();
            Log.write("透明度滚轮：" + transPct(alpha) + "%");
            render();
        } else {
            zoomAt(delta > 0 ? 1.0 / 1.1 : 1.1, cp);
        }
    }

    private void onKey(KeyEvent e) {
        if (TextInput.active()) return;
        int kc = e.getKeyCode();
        if (kc == KeyEvent.VK_ESCAPE) {
            if (!editing && alphaFly.visible) {
                alphaFly.hide();
                closeAlphaEdit();
                render();
                return;
            }
            if (editing && flyout.visible) {
                flyout.hide();
                render();
                return;
            }
            if (editing) {
                editing = false;
                shapeDrag = false;
                render();
            } else close();
            return;
        }
        if (editing) {
            if ((kc == KeyEvent.VK_DELETE || kc == KeyEvent.VK_BACK_SPACE) && ed.selected != null) {
                ed.beginOp();
                ed.shapes.remove(ed.selected);
                ed.selected = null;
                render();
                return;
            }
            if (kc == KeyEvent.VK_Z && e.isControlDown()) {
                if (e.isShiftDown()) ed.redoOp();
                else ed.undo();
                render();
                return;
            }
            Edit.Tool t = Edit.toolFromKey(kc);
            if (t != null) {
                ed.cur = t;
                render();
            }
        }
    }

    // ---------------- 视图 ----------------

    private final class View extends JComponent {
        View() {
            setDoubleBuffered(true);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            frame(g);
            if (BmpDump.enabled()) {
                BmpDump.write(Main.exeDir + "\\pinframe.bmp", d -> Pin.this.frame(d), ww, wh);
            }
        }
    }
}
