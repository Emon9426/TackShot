package tackshot;

import com.sun.jna.Pointer;

import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.Timer;
import java.awt.AWTException;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;

/** 区域截图主流程：多屏捕获 / 框选遮罩 / 窗口吸附 / 8 控制点 / 编辑宿主（对应 capture.cpp）。 */
final class Capture {
    private static Capture s;

    private BufferedImage base;      // 整个虚拟屏快照
    private int vx, vy, vw, vh;      // 虚拟屏原点与尺寸（物理像素）
    private java.awt.Window wnd;
    private View view;
    private int phase;              // 0=框选 1=编辑
    private boolean lbtn;
    private int mode;               // 0=新建选区 1=移动 2..9=控制点 10=吸附确认
    private final Point down = new Point();
    private Rectangle sel = new Rectangle();
    private boolean selValid;
    private final Edit.Editor ed = new Edit.Editor();
    private final Tb.Toolbar tb = new Tb.Toolbar();
    private int hover;
    private long hoverSince;
    private Pointer prevFocus;
    private boolean shapeDrag;
    private ArrayList<Nat.SnapWin> snaps = new ArrayList<>();
    private int snapIdx = -1;
    private long hintUntil;
    private final Tb.MosaicFlyout flyout = new Tb.MosaicFlyout();
    private long sizeHudUntil;
    private Timer tipT, animT, hudT;
    private final Cursor cross = Icon.crossCursor();

    private Capture() {}

    // ---------------- 入口 ----------------

    static void startRegion() {
        if (s != null) return;    // 会话进行中，忽略重入
        Capture c = new Capture();
        if (!c.captureScreens()) {
            Log.write("截图失败：无法捕获屏幕");
            Main.balloon("钉图 TackShot", "截图失败：无法捕获屏幕");
            return;
        }
        s = c;
        c.begin();
    }

    static void startFullscreen() {
        if (s != null) return;
        Capture c = new Capture();
        if (!c.captureScreens()) {
            Main.balloon("钉图 TackShot", "截图失败：无法捕获屏幕");
            return;
        }
        Log.write("全屏截图完成：" + c.vw + "x" + c.vh);
        Main.finishImage(c.base);
    }

    private boolean captureScreens() {
        Rectangle vb = new Rectangle(0, 0, 0, 0);
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration gc = gd.getDefaultConfiguration();
            vb = vb.union(gc.getBounds());
        }
        vx = vb.x;
        vy = vb.y;
        vw = vb.width;
        vh = vb.height;
        if (vw <= 0 || vh <= 0) return false;
        try {
            Robot robot = new Robot(GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice());
            base = robot.createScreenCapture(new Rectangle(vx, vy, vw, vh));
            return true;
        } catch (RuntimeException e) {
            Log.write("屏幕捕获异常：" + e);
            return false;
        } catch (AWTException e) {
            Log.write("屏幕捕获异常：Robot 创建失败 " + e);
            return false;
        }
    }

    private void begin() {
        phase = 0;
        selValid = false;
        lbtn = false;
        mode = 0;
        hover = 0;
        shapeDrag = false;
        hoverSince = 0;
        hintUntil = Main.nowMs() + 2800;
        ed.shapes.clear();
        ed.redo.clear();
        ed.cur = Edit.Tool.None;
        ed.widthIdx = 1;
        prevFocus = Nat.getForeground();
        snaps = new ArrayList<>(Nat.enumSnaps(new Rectangle(vx, vy, vw, vh)));
        for (int i = 0; i < snaps.size() && i < 10; i++) {
            Rectangle r = snaps.get(i).r;
            Log.write("吸附候选" + (i + 1) + ": [" + r.x + "," + r.y + " " + r.width + "x" + r.height + "] "
                    + snaps.get(i).title);
        }

        javax.swing.JFrame frame = new javax.swing.JFrame();
        frame.setUndecorated(true);
        frame.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setAlwaysOnTop(true);
        frame.setFocusableWindowState(true);
        frame.setBackground(Color.BLACK);
        view = new View();
        view.setOpaque(false);
        view.setFocusable(true);
        frame.setContentPane(view);
        frame.setBounds(vx, vy, vw, vh);
        wire(view);
        wnd = frame;

        tipT = new Timer(320, e -> repaint());
        tipT.setRepeats(false);
        animT = new Timer(40, e -> {
            repaint();
            if (hover == 0 || hoverSince == 0 || Main.nowMs() - hoverSince > 260) animT.stop();
        });
        hudT = new Timer(90, e -> {
            repaint();
            if (sizeHudUntil == 0 || Main.nowMs() > sizeHudUntil) hudT.stop();
        });

        wnd.setVisible(true);
        wnd.toFront();
        Nat.hideFromTaskbar(wnd);
        Nat.makeActivatable(wnd);
        Nat.forceForeground(wnd);
        view.requestFocusInWindow();
        wnd.setCursor(cross);
        Log.write("区域截图开始：虚拟屏 " + vw + "x" + vh + " @(" + vx + "," + vy + ")，吸附候选窗口 "
                + snaps.size() + " 个");
    }

    private void repaint() {
        if (view != null) view.repaint();
    }

    // ---------------- 几何辅助 ----------------

    private Rectangle toLocal(Rectangle r) {
        return new Rectangle(r.x - vx, r.y - vy, r.width, r.height);
    }

    private static Rectangle normSel(Point a, Point b) {
        return new Rectangle(Math.min(a.x, b.x), Math.min(a.y, b.y),
                Math.abs(b.x - a.x), Math.abs(b.y - a.y));
    }

    private static Point handlePt(Rectangle r, int i) {
        int cx = r.x + r.width / 2, cy = r.y + r.height / 2;
        int[] hx = {0, 1, 2, 2, 2, 1, 0, 0}, hy = {0, 0, 0, 1, 2, 2, 2, 1};
        int[] px = {r.x, cx, r.x + r.width}, py = {r.y, cy, r.y + r.height};
        return new Point(px[hx[i]], py[hy[i]]);
    }

    private int hitHandle(Point p) {
        int r = (int) (10 * Nat.dpiScale(wnd) + 0.5f);
        for (int i = 0; i < 8; i++) {
            Point h = handlePt(sel, i);
            if (Math.abs(p.x - h.x) <= r && Math.abs(p.y - h.y) <= r) return 2 + i;
        }
        return 0;
    }

    private static void applyHandle(Rectangle r, int m, Point p) {
        int idx = m - 2;
        if (idx == 0 || idx == 6 || idx == 7) {
            int oldRight = r.x + r.width;
            r.x = Math.min(p.x, oldRight - 8);
            r.width = oldRight - r.x;
        }
        if (idx == 2 || idx == 3 || idx == 4) r.width = Math.max(p.x - r.x, 8);
        if (idx == 0 || idx == 1 || idx == 2) {
            int oldBottom = r.y + r.height;
            r.y = Math.min(p.y, oldBottom - 8);
            r.height = oldBottom - r.y;
        }
        if (idx == 4 || idx == 5 || idx == 6) r.height = Math.max(p.y - r.y, 8);
    }

    private void clampSel(Rectangle r) {
        r.x = Math.max(r.x, vx);
        r.y = Math.max(r.y, vy);
        int right = Math.min(r.x + r.width, vx + vw);
        int bottom = Math.min(r.y + r.height, vy + vh);
        r.width = Math.max(8, right - r.x);
        r.height = Math.max(8, bottom - r.y);
    }

    private int findSnap(Point sp) {
        for (int i = 0; i < snaps.size(); i++)
            if (Tb.ptIn(snaps.get(i).r, sp.x, sp.y)) return i;
        return -1;
    }

    // ---------------- 渲染 ----------------

    private void render(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        float sc = Nat.dpiScale(wnd);

        g.drawImage(base, 0, 0, vw, vh, null);
        g.setColor(new Color(0, 0, 0, 118));
        g.fillRect(0, 0, vw, vh);

        // 窗口吸附预览（FR-1.3）
        if (!selValid && snapIdx >= 0 && snapIdx < snaps.size()) {
            Rectangle sr = new Rectangle(snaps.get(snapIdx).r);
            clampSel(sr);
            Rectangle sl = toLocal(sr);
            g.drawImage(base, sl.x, sl.y, sl.x + sl.width, sl.y + sl.height,
                    sl.x, sl.y, sl.x + sl.width, sl.y + sl.height, null);
            g.setStroke(new BasicStroke(1f));
            g.setColor(Color.WHITE);
            g.draw(new Rectangle2D.Float(sl.x, sl.y, sl.width - 1, sl.height - 1));
            g.setStroke(new BasicStroke(2f));
            g.setColor(new Color(59, 130, 246));
            g.draw(new Rectangle2D.Float(sl.x - 1, sl.y - 1, sl.width + 1, sl.height + 1));
            g.setFont(Tb.uiFont(12 * sc, Font.PLAIN));
            String t = "窗口 " + sl.width + "\u00D7" + sl.height + "（点击截取）";
            FontMetrics fm = g.getFontMetrics();
            float tw = (float) fm.getStringBounds(t, g).getWidth();
            float th = fm.getAscent();
            float orgX = sl.x;
            float orgY = sl.y - 24 > 2 ? sl.y - 24 : sl.y + sl.height + 6;
            g.setColor(new Color(15, 23, 42, 235));
            g.fill(new Rectangle2D.Float(orgX - 4, orgY - 2, tw + 8, th + 4));
            g.setColor(new Color(226, 232, 240));
            g.drawString(t, orgX, orgY + fm.getAscent() - (th - fm.getAscent()) / 2);
        }

        // 框选拖动中（含首次拖动）也必须实时渲染选区（FR-1.11）
        boolean draggingSel = lbtn && mode == 0;
        if (selValid || draggingSel) {
            Rectangle sl = toLocal(sel);
            g.drawImage(base, sl.x, sl.y, sl.x + sl.width, sl.y + sl.height,
                    sl.x, sl.y, sl.x + sl.width, sl.y + sl.height, null);
            if (draggingSel) {
                g.setColor(new Color(255, 255, 255, 64));
                g.fillRect(sl.x, sl.y, sl.width, sl.height);
            }
            java.awt.geom.AffineTransform save = g.getTransform();
            g.translate(sl.x, sl.y);
            Edit.drawShapes(g, ed.shapes, base, sl.x, sl.y);
            if (shapeDrag) Edit.drawShape(g, ed.draft, base, sl.x, sl.y);
            g.setTransform(save);
            g.setColor(new Color(59, 130, 246));
            g.setStroke(new BasicStroke(1.6f));
            g.draw(new Rectangle2D.Float(sl.x, sl.y, sl.width, sl.height));
            if (phase == 1) {
                int hs = (int) (10 * sc + 0.5f), ho = hs / 2;
                for (int i = 0; i < 8; i++) {
                    Point p = handlePt(sel, i);
                    Rectangle2D.Float hr = new Rectangle2D.Float(p.x - vx - ho, p.y - vy - ho, hs, hs);
                    g.setColor(new Color(59, 130, 246));
                    g.fill(hr);
                    g.setStroke(new BasicStroke(1.2f));
                    g.setColor(Color.WHITE);
                    g.draw(hr);
                }
            }
            if (lbtn && mode <= 1) {
                String t = sel.x + ", " + sel.y + " · " + sel.width + "\u00D7" + sel.height;
                g.setFont(new Font("Consolas", Font.PLAIN, (int) (13 * sc)));
                FontMetrics fm = g.getFontMetrics();
                float tw = (float) fm.getStringBounds(t, g).getWidth();
                float orgX = sl.x;
                float orgY = sl.y - 26 > 2 ? sl.y - 26 : sl.y + sl.height + 6;
                g.setColor(new Color(15, 23, 42, 235));
                g.fill(new Rectangle2D.Float(orgX - 4, orgY - 2, tw + 8, 18f));
                g.setColor(new Color(226, 232, 240));
                g.drawString(t, orgX, orgY + 13);
            }
        }

        if (phase == 1 && selValid) {
            tb.layout(toLocal(sel), new Rectangle(0, 0, vw, vh), Tb.MODE_EDITOR, sc);
            float hs = 1f;
            if (hover != 0 && hoverSince != 0) {
                double el = Main.nowMs() - hoverSince;
                float t = (float) Math.min(1.0, el / 160.0);
                hs = 1f + 0.30f * t * (2f - t);
            }
            tb.draw(g, ed, hover, Tb.MODE_EDITOR, hs);
            if (hover != 0 && hoverSince != 0 && Main.nowMs() - hoverSince > 300) {
                Point cp = java.awt.MouseInfo.getPointerInfo().getLocation();
                Tb.drawTooltip(g, new Point(cp.x - vx, cp.y - vy),
                        new Rectangle(0, 0, vw, vh), Tb.name(hover), sc);
            }
        }

        // 马赛克二级菜单与粒度 HUD（FR-3.15）
        if (flyout.visible) {
            for (Tb.TbBtn b : tb.btns)
                if (b.id == Tb.TB_MOSAIC) {
                    flyout.layout(b.r, new Rectangle(0, 0, vw, vh), sc);
                    break;
                }
            flyout.draw(g, ed, sc);
        }
        if (ed.cur == Edit.Tool.Mosaic && sizeHudUntil != 0 && Main.nowMs() < sizeHudUntil) {
            Point cp = java.awt.MouseInfo.getPointerInfo().getLocation();
            Tb.drawSizeHud(g, new Point(cp.x - vx, cp.y - vy), ed, sc);
        }

        // 操作引导条
        if (hintUntil != 0 && !selValid && Main.nowMs() < hintUntil) {
            String txt = "移到窗口上点击 = 整窗截取　｜　拖动 = 自由框选　｜　Esc 取消";
            g.setFont(Tb.uiFont(13f * sc, Font.PLAIN));
            FontMetrics fm = g.getFontMetrics();
            float tw = (float) fm.getStringBounds(txt, g).getWidth();
            float th = (float) fm.getStringBounds(txt, g).getHeight();
            float hx = (vw - tw) / 2f;
            float hy = (int) (36 * sc);
            g.setColor(new Color(15, 23, 42, 235));
            g.fill(new Rectangle2D.Float(hx - 12, hy - 7, tw + 24, th + 14));
            g.setColor(new Color(51, 65, 85, 255));
            g.setStroke(new BasicStroke(1f));
            g.draw(new Rectangle2D.Float(hx - 12, hy - 7, tw + 24, th + 14));
            g.setColor(new Color(241, 245, 249));
            g.drawString(txt, hx, hy + fm.getAscent());
        }
    }

    // ---------------- 事件 ----------------

    private void wire(JComponent v) {
        v.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                Capture.this.onPressed(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                Capture.this.onReleased(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    Point sp = new Point(e.getX() + vx, e.getY() + vy);
                    if (selValid && ed.cur == Edit.Tool.None && sel.contains(sp.x, sp.y)) confirm();
                }
            }
        });
        v.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                Capture.this.onMove(e.getX(), e.getY(), false);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                Capture.this.onMove(e.getX(), e.getY(), true);
            }
        });
        v.addMouseWheelListener(this::onWheel);
        v.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                Capture.this.onKey(e);
            }
        });
    }

    private void onMove(int x, int y, boolean drag) {
        if (wnd == null) return;
        Point sp = new Point(x + vx, y + vy);
        int hov = (phase == 1 && selValid) ? tb.hit(x, y) : 0;
        if (hov != hover) {
            hover = hov;
            hoverSince = Main.nowMs();
            tipT.stop();
            animT.stop();
            if (hov != 0) {
                tipT.start();     // 静止悬停不产生重绘事件，320ms 后补一次
                animT.start();
            }
            repaint();
        }
        if (!lbtn && !selValid) {
            int idx = findSnap(sp);
            if (idx != snapIdx) {
                snapIdx = idx;
                repaint();
            }
        }
            if (drag && lbtn) {
                if (mode == 0) {
                sel = normSel(down, sp);
                clampSel(sel);
            } else if (mode == 10) {
                if (Math.abs(sp.x - down.x) + Math.abs(sp.y - down.y) > (int) (6 * Nat.dpiScale(wnd))) {
                    mode = 0;
                    snapIdx = -1;
                    sel = normSel(down, sp);
                    clampSel(sel);
                }
            } else if (mode == 1) {
                sel.translate(sp.x - down.x, sp.y - down.y);
                down.setLocation(sp);
                clampSel(sel);
            } else if (mode >= 2) {
                applyHandle(sel, mode, sp);
                clampSel(sel);
            }
            if (shapeDrag) {
                Point cp = new Point(sp.x - sel.x, sp.y - sel.y);
                if (ed.cur == Edit.Tool.Pen) {
                    if (ed.draft.pts == null) ed.draft.pts = new ArrayList<>();
                    ed.draft.pts.add(new int[]{cp.x, cp.y});
                } else {
                    ed.draft.bx = cp.x;
                    ed.draft.by = cp.y;
                }
            }
            repaint();
        } else if (!drag) {
            updateCursor(x, y);
        }
    }

    private void onPressed(MouseEvent e) {
        if (wnd == null) return;
        view.requestFocusInWindow();
        int x = e.getX(), y = e.getY();
        Point sp = new Point(x + vx, y + vy);
        if (e.getButton() == MouseEvent.BUTTON3) {
            onRightDown(x, y);
            return;
        }
        if ((e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) == 0
                && e.getButton() != MouseEvent.BUTTON1) return;
        hintUntil = 0;
        if (phase == 1 && selValid) {
            if (flyout.visible) {
                int ms = flyout.hit(x, y);
                flyout.hide();
                if (ms != 0) {
                    ed.mosaicStyle = Tb.msToStyle(ms);
                    repaint();
                    return;
                }
            }
            int id = tb.hit(x, y);
            if (id == Tb.TB_MOSAIC) {
                Rectangle cz = Tb.mosaicCaretZone(tb);
                if (Tb.ptIn(cz, x, y)) {
                    for (Tb.TbBtn b : tb.btns)
                        if (b.id == Tb.TB_MOSAIC) {
                            flyout.layout(b.r, new Rectangle(0, 0, vw, vh), Nat.dpiScale(wnd));
                            break;
                        }
                    flyout.visible = true;
                    repaint();
                    return;
                }
                flyout.hide();
            }
            if (id != 0) {
                onToolbar(id);
                return;
            }
        }
        lbtn = true;
        down.setLocation(sp);
        if (!selValid) {
            if (snapIdx >= 0) mode = 10;
            else {
                mode = 0;
                sel = normSel(sp, sp);
            }
        } else {
            int hh = hitHandle(sp);
            if (hh != 0) {
                mode = hh;
            } else if (sel.contains(sp.x, sp.y)) {
                if (ed.cur == Edit.Tool.Text) {
                    final Point cp = new Point(sp.x - sel.x, sp.y - sel.y);
                    final int col = ed.color;
                    final int fs = Edit.fontSizeFor(ed.widthIdx);
                    lbtn = false;
                    TextInput.start(wnd, sp, fs, col, txt -> {
                        if (!txt.isEmpty() && wnd != null) {
                            Edit.Shape sh = new Edit.Shape();
                            sh.tool = Edit.Tool.Text;
                            sh.color = col;
                            sh.fontSize = fs;
                            sh.ax = cp.x;
                            sh.ay = cp.y;
                            sh.text = txt;
                            ed.push(sh);
                            repaint();
                        }
                    });
                    return;
                } else if (ed.cur != Edit.Tool.None) {
                    mode = -1;
                    shapeDrag = true;
                    Point cp = new Point(sp.x - sel.x, sp.y - sel.y);
                    ed.draft = new Edit.Shape();
                    ed.draft.tool = ed.cur;
                    ed.draft.color = ed.color;
                    ed.draft.penW = Edit.penWidth(ed.widthIdx);
                    ed.draft.fontSize = Edit.fontSizeFor(ed.widthIdx);
                    ed.draft.mStyle = ed.mosaicStyle;
                    ed.draft.mSize = ed.mosaicSize;
                    ed.draft.ax = cp.x;
                    ed.draft.ay = cp.y;
                    ed.draft.bx = cp.x;
                    ed.draft.by = cp.y;
                    if (ed.cur == Edit.Tool.Pen) {
                        ed.draft.pts = new ArrayList<>();
                        ed.draft.pts.add(new int[]{cp.x, cp.y});
                    }
                } else {
                    mode = 1;
                }
            } else {
                snapIdx = findSnap(sp);
                selValid = false;
                if (snapIdx >= 0) mode = 10;
                else {
                    mode = 0;
                    sel = normSel(sp, sp);
                }
            }
        }
        repaint();
    }

    private void onReleased(MouseEvent e) {
        if (!lbtn) return;
        lbtn = false;
        Point sp = new Point(e.getX() + vx, e.getY() + vy);
        if (mode == 10) {
            if (snapIdx >= 0 && snapIdx < snaps.size()) {
                sel = new Rectangle(snaps.get(snapIdx).r);
                clampSel(sel);
                selValid = true;
                phase = 1;
                Log.write("窗口吸附截取：" + sel.width + "\u00D7" + sel.height);
            }
        } else if (mode == 0) {
            if (sel.width >= 6 && sel.height >= 6) {
                selValid = true;
                phase = 1;
            }
        } else if (mode >= 2) {
            clampSel(sel);
        }
        if (shapeDrag) {
            shapeDrag = false;
            Edit.Shape d = ed.draft;
            boolean ok = d.tool == Edit.Tool.Pen
                    ? (d.pts != null && d.pts.size() > 1)
                    : (Math.abs(d.bx - d.ax) + Math.abs(d.by - d.ay) > 4);
            if (ok) ed.push(d);
            repaint();
        }
        mode = 0;
        repaint();
    }

    private void onRightDown(int x, int y) {
        if (phase == 1 && selValid && tb.hit(x, y) == Tb.TB_MOSAIC) {
            for (Tb.TbBtn b : tb.btns)
                if (b.id == Tb.TB_MOSAIC) {
                    flyout.layout(b.r, new Rectangle(0, 0, vw, vh), Nat.dpiScale(wnd));
                    break;
                }
            flyout.visible = !flyout.visible;
            repaint();
            return;
        }
        cancel();
    }

    private void onWheel(MouseWheelEvent e) {
        if (ed.cur == Edit.Tool.Mosaic) {
            int d = e.getWheelRotation();
            ed.mosaicSize = Math.min(80, Math.max(6, ed.mosaicSize + (d > 0 ? -4 : 4)));
            sizeHudUntil = Main.nowMs() + 800;
            hudT.start();
            repaint();
        }
    }

    private void onKey(KeyEvent e) {
        if (TextInput.active()) return;
        int kc = e.getKeyCode();
        if (kc == KeyEvent.VK_ESCAPE) {
            if (flyout.visible) {
                flyout.hide();
                repaint();
            } else cancel();
            return;
        }
        if (kc == KeyEvent.VK_ENTER) {
            if (selValid) confirm();
            return;
        }
        if (kc == KeyEvent.VK_Z && e.isControlDown()) {
            if (e.isShiftDown()) ed.redoOp();
            else ed.undo();
            repaint();
            return;
        }
        if (kc == KeyEvent.VK_Y && e.isControlDown()) {
            ed.redoOp();
            repaint();
            return;
        }
        Edit.Tool t = Edit.toolFromKey(kc);
        if (t != null) {
            ed.cur = t;
            repaint();
        }
    }

    private void updateCursor(int x, int y) {
        Cursor c;
        if (tb.hit(x, y) != 0) c = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        else if (!selValid || shapeDrag) c = cross;
        else if (hitHandle(new Point(x + vx, y + vy)) != 0)
            c = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR);
        else if (sel.contains(x + vx, y + vy))
            c = ed.cur != Edit.Tool.None ? cross : Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR);
        else c = Cursor.getDefaultCursor();
        view.setCursor(c);
    }

    // ---------------- 工具条动作 ----------------

    private void onToolbar(int id) {
        switch (id) {
            case Tb.TB_OK:
            case Tb.TB_PIN:
                confirm();
                return;
            case Tb.TB_SAVE:
                saveAndClose();
                return;
            case Tb.TB_CANCEL:
                cancel();
                return;
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
                break;
            case Tb.TB_W0: ed.widthIdx = 0; break;
            case Tb.TB_W1: ed.widthIdx = 1; break;
            case Tb.TB_W2: ed.widthIdx = 2; break;
            default: break;
        }
        repaint();
    }

    // ---------------- 会话结束 ----------------

    private void endSession(boolean restoreFocus) {
        if (TextInput.active()) TextInput.cancel();
        s = null;
        if (tipT != null) tipT.stop();
        if (animT != null) animT.stop();
        if (hudT != null) hudT.stop();
        if (wnd != null) {
            wnd.dispose();
            wnd = null;
            view = null;
        }
        if (restoreFocus && prevFocus != null) {
            Nat.setForeground(prevFocus);
            prevFocus = null;
        }
    }

    private void cancel() {
        endSession(true);
    }

    private BufferedImage compose() {
        int w = sel.width, h = sel.height;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int ox = sel.x - vx, oy = sel.y - vy;
        g.drawImage(base, 0, 0, w, h, ox, oy, ox + w, oy + h, null);
        Edit.drawShapes(g, ed.shapes, base, ox, oy);
        g.dispose();
        return out;
    }

    private void confirm() {
        if (!selValid) return;
        BufferedImage out = compose();
        endSession(true);
        Main.finishImage(out);
    }

    /** "保存"：自动保存到用户图片目录 + 复制剪贴板 + 结束会话（V2.0 交付语义）。 */
    private void saveAndClose() {
        if (!selValid) return;
        if (TextInput.active()) TextInput.cancel();
        BufferedImage out = compose();
        boolean copied = Clip.toClipboard(wnd, out);
        String dir = Main.cfg.outputDir.isEmpty() ? Out.defaultSaveDir() : Main.cfg.outputDir;
        boolean jpeg = Main.cfg.format.equals("jpeg");
        String path = Out.buildSavePath(dir, jpeg ? "jpg" : "png");
        boolean saved = Out.save(out, path, jpeg, Main.cfg.jpegQuality);
        endSession(true);
        String msg = saved
                ? (copied ? "已复制到剪贴板 · 已保存 " + path : "已保存 " + path)
                : (copied ? "已复制到剪贴板 · 自动保存失败" : "保存失败：无法写入目标文件");
        Main.balloon("钉图 TackShot", msg);
        Log.write("输出完成：" + msg);
    }

    // ---------------- 视图 ----------------

    private final class View extends JComponent {
        View() {
            setDoubleBuffered(true);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            render(g);
            if (BmpDump.enabled()) {
                BmpDump.write(Main.exeDir + "\\dragframe.bmp",
                        d -> { d.setComposite(AlphaComposite.SrcOver); Capture.this.render(d); }, vw, vh);
            }
        }
    }
}
