package tackshot;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;

/** 工具条 / 矢量图标 / 悬停提示 / 马赛克二级菜单 / HUD（1:1 移植 editor.cpp）。 */
final class Tb {
    private Tb() {}

    static final int MODE_EDITOR = 0, MODE_PIN_EDIT = 1, MODE_PIN_HOVER = 2;

    static final int TB_NONE = 0, TB_OK = 1, TB_PIN = 2, TB_SAVE = 3, TB_CANCEL = 4,
            TB_RECT = 5, TB_ELLIPSE = 6, TB_LINE = 7, TB_ARROW = 8, TB_PEN = 9,
            TB_TEXT = 10, TB_MOSAIC = 11, TB_HIGHLIGHT = 12, TB_UNDO = 13, TB_REDO = 14,
            TB_C0 = 15, TB_C1 = 16, TB_C2 = 17, TB_C3 = 18, TB_C4 = 19, TB_C5 = 20,
            TB_W0 = 21, TB_W1 = 22, TB_W2 = 23,
            TB_EDIT = 24, TB_COPYIMG = 25, TB_ZOOMOUT = 26, TB_ZOOMIN = 27,
            TB_OPAQUE = 28, TB_CLOSE = 29,
            TB_MS_MOSAIC = 30, TB_MS_BLUR = 31, TB_MS_BLACK = 32;

    static final int[] PALETTE = {
            0xFFEF4444, 0xFFF59E0B, 0xFF22C55E, 0xFF3B82F6, 0xFFFFFFFF, 0xFF111827 };

    /** UI 字体族：Java2D 无字体链回退，Segoe UI 不含中文字形，需用自带 CJK 的雅黑 UI。 */
    static final String UI_FAMILY = "Microsoft YaHei UI";

    /** C++ PtInRect 为闭区间（含右边/下边）。 */
    static boolean ptIn(Rectangle r, int x, int y) {
        return r != null && x >= r.x && x <= r.x + r.width && y >= r.y && y <= r.y + r.height;
    }

    static String name(int id) {
        switch (id) {
            case TB_OK: return "确认 (Enter)";
            case TB_PIN: return "贴图";
            case TB_SAVE: return "另存为…";
            case TB_CANCEL: return "取消 (Esc)";
            case TB_RECT: return "矩形 (R)";
            case TB_ELLIPSE: return "椭圆 (O)";
            case TB_LINE: return "直线 (L)";
            case TB_ARROW: return "箭头 (A)";
            case TB_PEN: return "画笔 (B)";
            case TB_TEXT: return "文字 (T)";
            case TB_MOSAIC: return "马赛克 (M)";
            case TB_HIGHLIGHT: return "高亮 (H)";
            case TB_UNDO: return "撤销 (Ctrl+Z)";
            case TB_REDO: return "重做 (Ctrl+Y)";
            case TB_C0: return "颜色：红";
            case TB_C1: return "颜色：黄";
            case TB_C2: return "颜色：绿";
            case TB_C3: return "颜色：蓝";
            case TB_C4: return "颜色：白";
            case TB_C5: return "颜色：黑";
            case TB_W0: return "细线";
            case TB_W1: return "中线";
            case TB_W2: return "粗线";
            case TB_EDIT: return "编辑";
            case TB_COPYIMG: return "复制";
            case TB_ZOOMOUT: return "缩小（也可滚轮）";
            case TB_ZOOMIN: return "放大（也可滚轮）";
            case TB_OPAQUE: return "透明度（左键循环 0/25/75%，右键精确调节）";
            case TB_CLOSE: return "关闭（Esc / 双击）";
            case TB_MS_MOSAIC: return "方格马赛克";
            case TB_MS_BLUR: return "高斯模糊";
            case TB_MS_BLACK: return "纯黑涂抹";
            default: return "";
        }
    }

    static int msToStyle(int ms) { return ms == TB_MS_BLACK ? 2 : (ms == TB_MS_BLUR ? 1 : 0); }

    static Font uiFont(float px, int style) {
        return new Font(UI_FAMILY, style, 12).deriveFont(px);
    }

    /** 各模式按钮条目 {id, 逻辑宽}（layout 与宽度计算共用）。 */
    static int[][] itemsFor(int mode) {
        if (mode == MODE_EDITOR) {
            return new int[][]{
                    {TB_OK, 19}, {TB_PIN, 19}, {TB_SAVE, 19}, {TB_CANCEL, 19},
                    {TB_RECT, 19}, {TB_ELLIPSE, 19}, {TB_LINE, 19}, {TB_ARROW, 19}, {TB_PEN, 19},
                    {TB_TEXT, 19}, {TB_MOSAIC, 19}, {TB_HIGHLIGHT, 19},
                    {TB_UNDO, 19}, {TB_REDO, 19},
                    {TB_C0, 14}, {TB_C1, 14}, {TB_C2, 14}, {TB_C3, 14}, {TB_C4, 14}, {TB_C5, 14},
                    {TB_W0, 14}, {TB_W1, 14}, {TB_W2, 14}};
        }
        if (mode == MODE_PIN_EDIT) {
            return new int[][]{
                    {TB_OK, 19}, {TB_CANCEL, 19},
                    {TB_RECT, 19}, {TB_ELLIPSE, 19}, {TB_LINE, 19}, {TB_ARROW, 19}, {TB_PEN, 19},
                    {TB_TEXT, 19}, {TB_MOSAIC, 19}, {TB_HIGHLIGHT, 19},
                    {TB_UNDO, 19}, {TB_REDO, 19},
                    {TB_C0, 14}, {TB_C1, 14}, {TB_C2, 14}, {TB_C3, 14}, {TB_C4, 14}, {TB_C5, 14},
                    {TB_W0, 14}, {TB_W1, 14}, {TB_W2, 14}};
        }
        return new int[][]{
                {TB_EDIT, 19}, {TB_COPYIMG, 19}, {TB_SAVE, 19},
                {TB_ZOOMOUT, 14}, {TB_NONE, 31}, {TB_ZOOMIN, 14},
                {TB_OPAQUE, 19}, {TB_CLOSE, 19}};
    }

    private static int S(float v, float scale) {
        return (int) (v * Math.max(1f, scale) + 0.5f);
    }

    /** 工具条总宽（与 layout 完全同口径：逐项取整累加）。 */
    static int totalW(int mode, float scale) {
        int[][] items = itemsFor(mode);
        int total = S(3, scale) * 2;
        for (int i = 0; i < items.length; i++)
            total += S(items[i][1], scale) + (i + 1 < items.length ? S(1, scale) : 0);
        return total;
    }

    /** 贴图窗口最小宽度：保证当前模式的菜单完整显示（含两侧 4px 边距）。 */
    static int pinMinWinW(boolean editing, float scale) {
        int w = totalW(MODE_PIN_HOVER, scale);
        if (editing) w = Math.max(w, totalW(MODE_PIN_EDIT, scale));
        return w + 2 * S(4, scale);
    }

    static final class TbBtn {
        final int id;
        final Rectangle r;
        TbBtn(int id, Rectangle r) { this.id = id; this.r = r; }
    }

    static final class Toolbar {
        ArrayList<TbBtn> btns = new ArrayList<>();
        Rectangle bar = new Rectangle();
        int zoomPct = 100;
        float scale = 1f;

        private int S(float v) { return (int) (v * scale + 0.5f); }

        void layout(Rectangle host, Rectangle scr, int mode, float dpiScale) {
            scale = Math.max(1f, dpiScale);
            btns = new ArrayList<>();
            int[][] items = itemsFor(mode);
            int gap = S(1), pad = S(3);
            int total = pad * 2;
            for (int i = 0; i < items.length; i++)
                total += S(items[i][1]) + (i + 1 < items.length ? gap : 0);
            int h = S(24);
            int cx = host.x + host.width / 2;
            int x = cx - total / 2;
            int y;
            if (mode == MODE_PIN_HOVER) {
                y = host.y + S(4);
            } else {
                y = host.y + host.height + S(8);
                if (y + h > scr.y + scr.height) y = host.y - S(8) - h;
                if (y < scr.y) y = scr.y;
            }
            if (x + total > scr.x + scr.width - S(4)) x = scr.x + scr.width - S(4) - total;
            if (x < scr.x + S(4)) x = scr.x + S(4);
            bar = new Rectangle(x, y, total, h);
            int cur = x + pad;
            for (int[] it : items) {
                btns.add(new TbBtn(it[0], new Rectangle(cur, y + S(2), S(it[1]), h - S(2))));
                cur += S(it[1]) + gap;
            }
        }

        int hit(int x, int y) {
            if (!ptIn(bar, x, y)) return 0;
            for (TbBtn b : btns)
                if (ptIn(b.r, x, y)) return b.id;
            return 0;
        }

        void draw(Graphics2D g, Edit.Editor ed, int hover, int mode, float hoverScale) {
            if (btns.isEmpty()) return;
            float rad = 9f * scale;
            Rectangle2D.Float bg = new Rectangle2D.Float(bar.x, bar.y, bar.width, bar.height);
            java.awt.geom.RoundRectangle2D.Float panel =
                    new java.awt.geom.RoundRectangle2D.Float(bar.x, bar.y, bar.width, bar.height, rad * 2, rad * 2);
            g.setColor(new Color(27, 33, 44, 235));
            g.fill(panel);
            g.setColor(new Color(51, 65, 85, 255));
            g.setStroke(new BasicStroke(1f));
            g.draw(panel);

            Edit.Tool curTool = ed != null ? ed.cur : Edit.Tool.None;
            int curW = ed != null ? ed.widthIdx : 1;

            for (TbBtn b : btns) {
                Rectangle2D.Float r = new Rectangle2D.Float(
                        b.r.x + 2, b.r.y + 2, b.r.width - 4, b.r.height - 4);
                if (b.id == TB_NONE) {
                    g.setColor(new Color(148, 163, 184, 255));
                    g.setFont(uiFont(8.5f * scale, Font.PLAIN));
                    drawStringCentered(g, zoomPct + "%", r);
                    continue;
                }
                if (hoverScale > 1.001f && b.id == hover) {
                    float gr = r.width * (hoverScale - 1f) * 0.5f + 0.5f;
                    r = new Rectangle2D.Float(r.x - gr, r.y - gr, r.width + gr * 2, r.height + gr * 2);
                }
                boolean active = false;
                if (ed != null) {
                    if (b.id == TB_RECT) active = curTool == Edit.Tool.Rect;
                    else if (b.id == TB_ELLIPSE) active = curTool == Edit.Tool.Ellipse;
                    else if (b.id == TB_LINE) active = curTool == Edit.Tool.Line;
                    else if (b.id == TB_ARROW) active = curTool == Edit.Tool.Arrow;
                    else if (b.id == TB_PEN) active = curTool == Edit.Tool.Pen;
                    else if (b.id == TB_TEXT) active = curTool == Edit.Tool.Text;
                    else if (b.id == TB_MOSAIC) active = curTool == Edit.Tool.Mosaic;
                    else if (b.id == TB_HIGHLIGHT) active = curTool == Edit.Tool.Highlight;
                }
                if (b.id == TB_W0) active = curW == 0;
                else if (b.id == TB_W1) active = curW == 1;
                else if (b.id == TB_W2) active = curW == 2;
                boolean hov = hover == b.id;
                if (active) {
                    g.setColor(new Color(37, 99, 235, 255));
                    g.fill(r);
                } else if (hov) {
                    g.setColor(new Color(51, 65, 85, 70));
                    g.fill(r);
                }
                glyph(g, b.id, r, ed);
                if (b.id == TB_MOSAIC) {
                    GeneralPath tp = new GeneralPath();
                    float rr = (float) r.getMaxX(), bb = (float) r.getMaxY();
                    tp.moveTo(rr - 4, bb - 5);
                    tp.lineTo(rr - 8, bb - 5);
                    tp.lineTo(rr - 6, bb - 2);
                    tp.closePath();
                    g.setColor(new Color(148, 163, 184, 255));
                    g.fill(tp);
                }
            }
        }

        private static void drawStringCentered(Graphics2D g, String s, Rectangle2D r) {
            FontMetrics fm = g.getFontMetrics();
            float w = (float) fm.getStringBounds(s, g).getWidth();
            g.drawString(s, (float) (r.getX() + (r.getWidth() - w) / 2),
                    (float) (r.getY() + (r.getHeight() - fm.getHeight()) / 2 + fm.getAscent()));
        }
    }

    private static void line(Graphics2D g, Color c, float w, float x1, float y1, float x2, float y2) {
        g.setColor(c);
        g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new java.awt.geom.Line2D.Float(x1, y1, x2, y2));
    }

    static void glyph(Graphics2D g, int id, Rectangle2D.Float r, Edit.Editor ed) {
        float w = r.width, h = r.height;
        float m = w * 0.2f;
        float l = (float) r.getX() + m, rt = (float) r.getX() + w - m;
        float tp = (float) r.getY() + m, bm = (float) r.getY() + h - m;
        float mx = (float) r.getX() + w / 2, my = (float) r.getY() + h / 2;
        float pw = h * 0.16f, pwT = h * 0.22f;
        switch (id) {
            case TB_OK:
                line(g, new Color(34, 197, 94), pwT, l, my + h * 0.05f, mx - w * 0.06f, bm);
                line(g, new Color(34, 197, 94), pwT, mx - w * 0.06f, bm, rt, tp);
                break;
            case TB_PIN: {
                line(g, new Color(148, 163, 184), pw * 0.8f, mx, my + h * 0.05f, mx + w * 0.14f, bm);
                g.setColor(new Color(59, 130, 246));
                g.fill(new Ellipse2D.Float(mx - w * 0.2f, tp, w * 0.44f, h * 0.44f));
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(pw * 0.45f));
                g.draw(new Ellipse2D.Float(mx - w * 0.2f, tp, w * 0.44f, h * 0.44f));
                break;
            }
            case TB_SAVE: {
                line(g, new Color(245, 158, 11), pw, mx, tp, mx, my + h * 0.04f);
                GeneralPath tri = new GeneralPath();
                tri.moveTo(mx - w * 0.14f, my - h * 0.02f);
                tri.lineTo(mx, my + h * 0.12f);
                tri.lineTo(mx + w * 0.14f, my - h * 0.02f);
                tri.closePath();
                g.setColor(new Color(245, 158, 11));
                g.fill(tri);
                line(g, new Color(148, 163, 184), pw * 0.7f, l, bm, rt, bm);
                break;
            }
            case TB_CANCEL:
                line(g, new Color(239, 68, 68), pwT, l, tp, rt, bm);
                line(g, new Color(239, 68, 68), pwT, l, bm, rt, tp);
                break;
            case TB_RECT:
                g.setColor(new Color(59, 130, 246));
                g.setStroke(new BasicStroke(pw));
                g.draw(new Rectangle2D.Float((float) r.getX() + w * 0.18f, (float) r.getY() + h * 0.24f,
                        w * 0.64f, h * 0.52f));
                break;
            case TB_ELLIPSE:
                g.setColor(new Color(20, 184, 166));
                g.setStroke(new BasicStroke(pw));
                g.draw(new Ellipse2D.Float((float) r.getX() + w * 0.16f, (float) r.getY() + h * 0.22f,
                        w * 0.68f, h * 0.56f));
                break;
            case TB_LINE:
                line(g, new Color(148, 163, 184), pw, l, bm, rt, tp);
                break;
            case TB_ARROW: {
                float ex = rt - w * 0.16f, ey = tp + h * 0.16f;
                line(g, new Color(99, 102, 241), pw, l, bm, ex, ey);
                GeneralPath hd = new GeneralPath();
                hd.moveTo(ex - w * 0.04f, ey - h * 0.22f);
                hd.lineTo(ex, ey);
                hd.lineTo(ex + w * 0.22f, ey - h * 0.04f);
                hd.closePath();
                g.setColor(new Color(99, 102, 241));
                g.fill(hd);
                break;
            }
            case TB_PEN: {
                float[][] pts = {
                        {l, my}, {mx - w * 0.14f, tp + h * 0.02f},
                        {mx + w * 0.14f, bm - h * 0.02f}, {rt, my - h * 0.08f}};
                g.setColor(new Color(249, 115, 22));
                g.setStroke(new BasicStroke(pwT, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(Edit.curve(pts));
                break;
            }
            case TB_TEXT: {
                g.setFont(uiFont(h * 0.68f, Font.BOLD));
                g.setColor(new Color(139, 92, 246));
                FontMetrics fm = g.getFontMetrics();
                float tw = (float) fm.getStringBounds("T", g).getWidth();
                g.drawString("T", (float) r.getX() + (w - tw) / 2,
                        (float) r.getY() + (h - fm.getHeight()) / 2 + fm.getAscent() - h * 0.02f);
                break;
            }
            case TB_MOSAIC: {
                float cw = w * 0.62f / 3f, ch = h * 0.62f / 3f, gp = cw * 0.2f;
                float ox = (float) r.getX() + w * 0.19f, oy = (float) r.getY() + h * 0.19f;
                for (int yy = 0; yy < 3; yy++)
                    for (int xx = 0; xx < 3; xx++) {
                        g.setColor(((xx + yy) & 1) == 1 ? new Color(165, 180, 196) : new Color(100, 116, 139));
                        g.fill(new Rectangle2D.Float(ox + xx * (cw + gp), oy + yy * (ch + gp), cw, ch));
                    }
                break;
            }
            case TB_HIGHLIGHT: {
                Rectangle2D.Float hr = new Rectangle2D.Float(
                        (float) r.getX() + w * 0.2f, (float) r.getY() + h * 0.26f, w * 0.6f, h * 0.48f);
                g.setColor(new Color(250, 204, 21, 200));
                g.fill(hr);
                g.setColor(new Color(245, 158, 11));
                g.setStroke(new BasicStroke(pw * 0.55f));
                g.draw(hr);
                break;
            }
            case TB_UNDO: {
                Rectangle2D.Float arcR = new Rectangle2D.Float(
                        (float) r.getX() + w * 0.2f, (float) r.getY() + h * 0.18f, w * 0.6f, h * 0.6f);
                g.setColor(new Color(148, 163, 184));
                g.setStroke(new BasicStroke(pw));
                g.draw(new Arc2D.Float(arcR, 180f, 240f, Arc2D.OPEN));
                g.fill(new Ellipse2D.Float(arcR.x - pw * 0.45f, arcR.y - pw * 0.25f, pw * 0.9f, pw * 0.9f));
                line(g, new Color(148, 163, 184), pw, arcR.x, arcR.y,
                        (float) r.getX() + w * 0.44f, arcR.y);
                break;
            }
            case TB_REDO: {
                Rectangle2D.Float arcR = new Rectangle2D.Float(
                        (float) r.getX() + w * 0.2f, (float) r.getY() + h * 0.18f, w * 0.6f, h * 0.6f);
                g.setColor(new Color(148, 163, 184));
                g.setStroke(new BasicStroke(pw));
                g.draw(new Arc2D.Float(arcR, -60f, 240f, Arc2D.OPEN));
                g.fill(new Ellipse2D.Float(rt - pw * 0.45f, arcR.y - pw * 0.25f, pw * 0.9f, pw * 0.9f));
                line(g, new Color(148, 163, 184), pw, rt, arcR.y,
                        (float) r.getX() + w * 0.56f, arcR.y);
                break;
            }
            case TB_C0: case TB_C1: case TB_C2: case TB_C3: case TB_C4: case TB_C5: {
                int col = PALETTE[id - TB_C0];
                int fill = col == 0xFF111827 ? 0xFF283040 : col;
                float d = r.width * 0.72f;
                float cx2 = (float) r.getX() + (r.width - d) / 2, cy2 = (float) r.getY() + (r.height - d) / 2;
                g.setColor(new Color(fill, true));
                g.fill(new Ellipse2D.Float(cx2, cy2, d, d));
                if (ed != null && ed.color == col) {
                    g.setColor(new Color(96, 165, 250));
                    g.setStroke(new BasicStroke(2f));
                    g.draw(new Ellipse2D.Float(cx2 - 2, cy2 - 2, d + 4, d + 4));
                }
                break;
            }
            case TB_W0: case TB_W1: case TB_W2:
                line(g, new Color(226, 232, 240), Edit.penWidth(id - TB_W0), l, my, rt, my);
                break;
            case TB_EDIT:
                line(g, new Color(245, 158, 11), pwT, (float) r.getX() + w * 0.3f, bm,
                        rt - w * 0.08f, tp + h * 0.08f);
                line(g, new Color(148, 163, 184), pw * 0.55f, (float) r.getX() + w * 0.18f, bm - h * 0.12f,
                        (float) r.getX() + w * 0.3f, bm);
                line(g, new Color(148, 163, 184), pw * 0.55f, (float) r.getX() + w * 0.3f, bm,
                        (float) r.getX() + w * 0.44f, bm - h * 0.12f);
                break;
            case TB_COPYIMG:
                g.setColor(new Color(14, 165, 233));
                g.setStroke(new BasicStroke(pw * 0.7f));
                g.draw(new Rectangle2D.Float((float) r.getX() + w * 0.34f, tp, w * 0.46f, h * 0.46f));
                g.draw(new Rectangle2D.Float(l, (float) r.getY() + h * 0.34f, w * 0.46f, h * 0.46f));
                break;
            case TB_ZOOMOUT: case TB_ZOOMIN: {
                float d = w * 0.62f;
                float mgx = (float) r.getX() + w * 0.08f, mgy = (float) r.getY() + h * 0.08f;
                g.setColor(new Color(14, 165, 233));
                g.setStroke(new BasicStroke(pw * 0.8f));
                g.draw(new Ellipse2D.Float(mgx, mgy, d, d));
                line(g, new Color(14, 165, 233), pw * 0.8f,
                        (float) r.getX() + w * 0.52f, (float) r.getY() + h * 0.52f, rt, bm);
                float cxx = mgx + d / 2, cyy = mgy + d / 2;
                line(g, new Color(226, 232, 240), pw * 0.7f, cxx - w * 0.13f, cyy, cxx + w * 0.13f, cyy);
                if (id == TB_ZOOMIN)
                    line(g, new Color(226, 232, 240), pw * 0.7f, cxx, cyy - w * 0.13f, cxx, cyy + w * 0.13f);
                break;
            }
            case TB_OPAQUE: {
                float d = w * 0.66f;
                Rectangle2D.Float cr = new Rectangle2D.Float(
                        (float) r.getX() + w * 0.17f, (float) r.getY() + h * 0.17f, d, d);
                g.setColor(new Color(59, 130, 246));
                g.fill(new Arc2D.Float(cr, -90f, 180f, Arc2D.PIE));
                g.setColor(new Color(147, 197, 253));
                g.setStroke(new BasicStroke(pw * 0.6f));
                g.draw(new Ellipse2D.Float(cr.x, cr.y, cr.width, cr.height));
                break;
            }
            case TB_CLOSE:
                line(g, new Color(239, 68, 68), pwT, l, tp, rt, bm);
                line(g, new Color(239, 68, 68), pwT, l, bm, rt, tp);
                break;
            default:
                break;
        }
    }

    // ---- 悬停提示条（FR-3.14） ----
    static void drawTooltip(Graphics2D g, Point pt, Rectangle clip, String text, float scale) {
        if (text == null || text.isEmpty()) return;
        g.setFont(uiFont(12f * scale, Font.PLAIN));
        FontMetrics fm = g.getFontMetrics();
        java.awt.geom.Rectangle2D bb = fm.getStringBounds(text, g);
        float pad = 5f * scale;
        float bw = (float) bb.getWidth() + pad * 2, bh = (float) bb.getHeight() + pad * 1.6f;
        float x = pt.x + 10f * scale;
        float y = pt.y - bh - 8f * scale;
        if (x + bw > clip.x + clip.width - 4) x = pt.x - bw - 10f * scale;
        if (x < clip.x + 4) x = clip.x + 4;    // 窄窗口：翻转后仍可能越左缘
        if (y < clip.y + 4) y = pt.y + 14f * scale;
        g.setColor(new Color(15, 23, 42, 242));
        g.fill(new Rectangle2D.Float(x, y, bw, bh));
        g.setColor(new Color(51, 65, 85, 255));
        g.setStroke(new BasicStroke(1f));
        g.draw(new Rectangle2D.Float(x, y, bw, bh));
        g.setColor(new Color(241, 245, 249));
        g.drawString(text, x + pad, y + pad * 0.6f + fm.getAscent());
    }

    // ---- 马赛克样式二级菜单（FR-3.15） ----
    static final class MosaicFlyout {
        Rectangle bar = new Rectangle();
        boolean visible = false;

        void layout(Rectangle anchorBtn, Rectangle clip, float scale) {
            int S = (int) Math.max(1f, scale);
            int w = sc(104, scale), rowH = sc(22, scale), h = rowH * 3 + sc(8, scale);
            int x = anchorBtn.x;
            int y = anchorBtn.y + anchorBtn.height + sc(4, scale);
            if (x + w > clip.x + clip.width - 4) x = clip.x + clip.width - 4 - w;
            if (x < clip.x + 4) x = clip.x + 4;
            if (y + h > clip.y + clip.height - 4) y = anchorBtn.y - sc(4, scale) - h;
            if (y < clip.y + 4) y = clip.y + 4;
            bar = new Rectangle(x, y, w, h);
        }

        private static int sc(float v, float scale) {
            return (int) (v * Math.max(1f, scale) + 0.5f);
        }

        void draw(Graphics2D g, Edit.Editor ed, float scale) {
            if (!visible) return;
            int fx = bar.x, fy = bar.y, fw = bar.width, fh = bar.height;
            g.setColor(new Color(15, 23, 42, 243));
            g.fill(new Rectangle2D.Float(fx, fy, fw, fh));
            g.setColor(new Color(51, 65, 85, 255));
            g.setStroke(new BasicStroke(1f));
            g.draw(new Rectangle2D.Float(fx, fy, fw, fh));
            int[] ids = {TB_MS_MOSAIC, TB_MS_BLUR, TB_MS_BLACK};
            g.setFont(uiFont(12f * scale, Font.PLAIN));
            int rowH = sc(22, scale);
            for (int i = 0; i < 3; i++) {
                int y = fy + sc(4, scale) + i * rowH;
                boolean sel = ed.mosaicStyle == i;
                if (sel) {
                    g.setColor(new Color(37, 99, 235, 255));
                    g.fill(new Rectangle2D.Float(fx + sc(3, scale), y, fw - sc(6, scale), rowH - sc(2, scale)));
                    g.setColor(new Color(96, 165, 250));
                    g.fill(new Ellipse2D.Float(fx + sc(8, scale), y + rowH / 2f - sc(4, scale),
                            sc(7, scale), sc(7, scale)));
                }
                g.setColor(new Color(241, 245, 249));
                g.drawString(name(ids[i]), fx + sc(20, scale), y + sc(4, scale) + g.getFontMetrics().getAscent());
            }
        }

        int hit(int x, int y) {
            if (!visible || !ptIn(bar, x, y)) return 0;
            int rowH = Math.max(1, bar.height / 3);
            int idx = (y - bar.y) / rowH;
            idx = Math.max(0, Math.min(2, idx));
            return new int[]{TB_MS_MOSAIC, TB_MS_BLUR, TB_MS_BLACK}[idx];
        }

        void hide() { visible = false; }
    }

    /** 通用文字 HUD（透明度等）。 */
    static void drawTextHud(Graphics2D g, Point pt, String text, float scale) {
        g.setFont(uiFont(13f * scale, Font.PLAIN));
        FontMetrics fm = g.getFontMetrics();
        java.awt.geom.Rectangle2D bb = fm.getStringBounds(text, g);
        float x = pt.x + 14f * scale, y = pt.y - (float) bb.getHeight() - 10f * scale;
        g.setColor(new Color(15, 23, 42, 243));
        g.fill(new Rectangle2D.Float(x - 6, y - 4, (float) bb.getWidth() + 12, (float) bb.getHeight() + 8));
        g.setColor(new Color(51, 65, 85, 255));
        g.setStroke(new BasicStroke(1f));
        g.draw(new Rectangle2D.Float(x - 6, y - 4, (float) bb.getWidth() + 12, (float) bb.getHeight() + 8));
        g.setColor(new Color(241, 245, 249));
        g.drawString(text, x, y + fm.getAscent());
    }

    /** 马赛克粒度 HUD。 */
    static void drawSizeHud(Graphics2D g, Point pt, Edit.Editor ed, float scale) {
        String t = ed.mosaicStyle == 2 ? "纯黑涂抹"
                : (ed.mosaicStyle == 1 ? "模糊" : "方格") + " " + ed.mosaicSize + "px";
        drawTextHud(g, pt, t, scale);
    }

    /** 马赛克按钮右下角 ▾ 角标区（展开二级菜单用）。 */
    static Rectangle mosaicCaretZone(Toolbar tb) {
        for (TbBtn b : tb.btns)
            if (b.id == TB_MOSAIC)
                return new Rectangle(b.r.x + b.r.width - 10, b.r.y + b.r.height - 10, 10, 10);
        return new Rectangle();
    }
}
