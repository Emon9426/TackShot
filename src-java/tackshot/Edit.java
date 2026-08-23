package tackshot;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;

/** 标注对象模型与绘制（截图编辑与贴图就地编辑共用）。 */
final class Edit {
    private Edit() {}

    enum Tool { None, Select, Rect, Ellipse, Line, Arrow, Pen, Text, Mosaic, Highlight }

    static final class Shape {
        Tool tool = Tool.Rect;
        int color = 0xFFEF4444;
        float penW = 4f;
        int ax, ay, bx, by;
        ArrayList<int[]> pts;      // 画笔轨迹 {x,y}
        String text = "";
        int fontSize = 26;
        int mStyle = 0;            // 0 方格 1 模糊 2 纯黑
        int mSize = 20;            // 粒度 6..80
    }

    static final class Editor {
        ArrayList<Shape> shapes = new ArrayList<>();
        private final ArrayList<ArrayList<Shape>> undoHist = new ArrayList<>(), redoHist = new ArrayList<>();
        Tool cur = Tool.None;
        int color = 0xFFEF4444;
        int widthIdx = 1;
        int mosaicStyle = 0, mosaicSize = 20;
        Shape draft = new Shape();
        Shape selected;             // 选择工具（FR-3.10）当前选中对象

        /** 任何对象操作（新增/移动/缩放/删除/改属性）前调用：快照入撤销栈（V2.2 快照式撤销）。 */
        void beginOp() {
            if (undoHist.size() > 200) undoHist.remove(0);
            undoHist.add(copyShapes(shapes));
            redoHist.clear();
        }

        void push(Shape s) {
            beginOp();
            shapes.add(s);
        }

        boolean undo() {
            if (undoHist.isEmpty()) return false;
            redoHist.add(copyShapes(shapes));
            shapes = undoHist.remove(undoHist.size() - 1);
            fixSelection();
            return true;
        }

        boolean redoOp() {
            if (redoHist.isEmpty()) return false;
            undoHist.add(copyShapes(shapes));
            shapes = redoHist.remove(redoHist.size() - 1);
            fixSelection();
            return true;
        }

        private void fixSelection() {
            if (selected != null && !shapes.contains(selected)) selected = null;
        }

        void reset() {
            shapes = new ArrayList<>();
            undoHist.clear();
            redoHist.clear();
            selected = null;
            draft = new Shape();
        }
    }

    static final int[] PEN_WIDTHS = {2, 4, 7};
    static final int[] FONT_SIZES = {18, 26, 36};

    static int penWidth(int idx) { return PEN_WIDTHS[((idx % 3) + 3) % 3]; }
    static int fontSizeFor(int idx) { return FONT_SIZES[((idx % 3) + 3) % 3]; }

    static Tool toolFromKey(int keyCode) {
        switch (keyCode) {
            case 'V': return Tool.Select;
            case 'R': return Tool.Rect;
            case 'O': return Tool.Ellipse;
            case 'L': return Tool.Line;
            case 'A': return Tool.Arrow;
            case 'B': return Tool.Pen;
            case 'T': return Tool.Text;
            case 'M': return Tool.Mosaic;
            case 'H': return Tool.Highlight;
            default: return null;
        }
    }

    // ---------------- 对象编辑（FR-3.10 · V2.2）：拷贝 / 命中 / 变换 / 选中态 ----------------

    static Shape copy(Shape s) {
        Shape n = new Shape();
        n.tool = s.tool;
        n.color = s.color;
        n.penW = s.penW;
        n.ax = s.ax;
        n.ay = s.ay;
        n.bx = s.bx;
        n.by = s.by;
        n.text = s.text;
        n.fontSize = s.fontSize;
        n.mStyle = s.mStyle;
        n.mSize = s.mSize;
        if (s.pts != null) {
            n.pts = new ArrayList<>();
            for (int[] p : s.pts) n.pts.add(new int[]{p[0], p[1]});
        }
        return n;
    }

    static ArrayList<Shape> copyShapes(ArrayList<Shape> v) {
        ArrayList<Shape> out = new ArrayList<>(v.size());
        for (Shape s : v) out.add(copy(s));
        return out;
    }

    private static Graphics2D fmG;

    private static FontMetrics fmFor(int size) {
        if (fmG == null)
            fmG = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        fmG.setFont(new Font(Tb.UI_FAMILY, Font.PLAIN, size));
        return fmG.getFontMetrics();
    }

    /** 对象包围盒（图像坐标）：文字按字宽度量，画笔按轨迹范围。 */
    static Rectangle bounds(Shape s) {
        switch (s.tool) {
            case Text: {
                int w = Math.max(6, fmFor(s.fontSize).stringWidth(s.text));
                return new Rectangle(s.ax, s.ay - (int) (s.fontSize * 1.05f), w,
                        (int) (s.fontSize * 1.25f));
            }
            case Pen: {
                if (s.pts == null || s.pts.isEmpty())
                    return new Rectangle(s.ax - 4, s.ay - 4, 8, 8);
                int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
                for (int[] p : s.pts) {
                    x0 = Math.min(x0, p[0]);
                    y0 = Math.min(y0, p[1]);
                    x1 = Math.max(x1, p[0]);
                    y1 = Math.max(y1, p[1]);
                }
                return new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
            }
            default:
                return new Rectangle(Math.min(s.ax, s.bx), Math.min(s.ay, s.by),
                        Math.abs(s.bx - s.ax), Math.abs(s.by - s.ay));
        }
    }

    private static double distSeg(int px, int py, int x1, int y1, int x2, int y2) {
        long dx = x2 - x1, dy = y2 - y1;
        double len2 = (double) dx * dx + dy * dy;
        double t = len2 <= 0 ? 0 : ((px - x1) * (double) dx + (py - y1) * (double) dy) / len2;
        t = Math.max(0, Math.min(1, t));
        double qx = x1 + t * dx, qy = y1 + t * dy;
        return Math.hypot(px - qx, py - qy);
    }

    /** 命中检测：返回最上层被点中的对象下标（线/箭头/画笔按笔画距离，其余按包围盒），-1 未命中。 */
    static int hitShape(ArrayList<Shape> v, int x, int y) {
        for (int i = v.size() - 1; i >= 0; i--) {
            Shape s = v.get(i);
            int tol = 6 + (int) (s.penW / 2f);
            if (s.tool == Tool.Line || s.tool == Tool.Arrow) {
                if (distSeg(x, y, s.ax, s.ay, s.bx, s.by) <= tol + 3) return i;
            } else if (s.tool == Tool.Pen) {
                if (s.pts == null || s.pts.isEmpty()) continue;
                if (s.pts.size() == 1) {
                    int[] q = s.pts.get(0);
                    if (Math.hypot(x - q[0], y - q[1]) <= tol + 2) return i;
                    continue;
                }
                for (int k = 1; k < s.pts.size(); k++) {
                    int[] a = s.pts.get(k - 1), b = s.pts.get(k);
                    if (distSeg(x, y, a[0], a[1], b[0], b[1]) <= tol + 2) return i;
                }
            } else {
                Rectangle b = bounds(s);
                if (x >= b.x - tol && x <= b.x + b.width + tol
                        && y >= b.y - tol && y <= b.y + b.height + tol) return i;
            }
        }
        return -1;
    }

    /** 对象控制点命中：0..7＝包围盒 8 向；直线/箭头 8=起点 9=终点；-1 未命中。tolPx 为命中半径。 */
    static int handleAt(Shape s, int x, int y, int tolPx) {
        if (s.tool == Tool.Line || s.tool == Tool.Arrow) {
            if (Math.abs(x - s.ax) <= tolPx && Math.abs(y - s.ay) <= tolPx) return 8;
            if (Math.abs(x - s.bx) <= tolPx && Math.abs(y - s.by) <= tolPx) return 9;
            return -1;
        }
        Rectangle b = bounds(s);
        int cx = b.x + b.width / 2, cy = b.y + b.height / 2;
        int[] hx = {b.x, cx, b.x + b.width, b.x + b.width, b.x + b.width, cx, b.x, b.x};
        int[] hy = {b.y, b.y, b.y, cy, b.y + b.height, b.y + b.height, b.y + b.height, cy};
        for (int i = 0; i < 8; i++)
            if (Math.abs(x - hx[i]) <= tolPx && Math.abs(y - hy[i]) <= tolPx) return i;
        return -1;
    }

    /** 整体平移。 */
    static void translate(Shape s, int dx, int dy) {
        s.ax += dx;
        s.ay += dy;
        s.bx += dx;
        s.by += dy;
        if (s.pts != null)
            for (int[] p : s.pts) {
                p[0] += dx;
                p[1] += dy;
            }
    }

    /** 包围盒拖拽重构：ob=拖拽前包围盒，nb=拖拽后包围盒。线/箭头/画笔按线性映射，文字按缩放系数改字号。 */
    static void reshape(Shape s, Rectangle ob, Rectangle nb) {
        if (nb.width < 4) nb.width = 4;
        if (nb.height < 4) nb.height = 4;
        double sx = nb.width / (double) ob.width, sy = nb.height / (double) ob.height;
        switch (s.tool) {
            case Rect:
            case Ellipse:
            case Mosaic:
            case Highlight:
                s.ax = nb.x;
                s.ay = nb.y;
                s.bx = nb.x + nb.width;
                s.by = nb.y + nb.height;
                break;
            case Line:
            case Arrow:
            case Pen: {
                java.util.function.IntUnaryOperator fx = v -> nb.x + (int) Math.round((v - ob.x) * sx);
                java.util.function.IntUnaryOperator fy = v -> nb.y + (int) Math.round((v - ob.y) * sy);
                int nax = fx.applyAsInt(s.ax), nay = fy.applyAsInt(s.ay);
                int nbx = fx.applyAsInt(s.bx), nby = fy.applyAsInt(s.by);
                s.ax = nax;
                s.ay = nay;
                s.bx = nbx;
                s.by = nby;
                if (s.pts != null)
                    for (int[] p : s.pts) {
                        p[0] = fx.applyAsInt(p[0]);
                        p[1] = fy.applyAsInt(p[1]);
                    }
                break;
            }
            case Text: {
                double f = Math.max(0.15, Math.min(6.0, (sx + sy) / 2.0));
                int ns = Math.max(10, Math.min(140, (int) Math.round(s.fontSize * f)));
                s.fontSize = ns;
                s.ax = nb.x;
                s.ay = nb.y + (int) (ns * 1.05f);
                break;
            }
            default:
                break;
        }
    }

    /** 拖动控制点：hd 0..7＝包围盒 8 向（ob=拖拽前包围盒），8/9＝线端点；cp 为当前图像坐标。 */
    static void applyHandle(Shape s, int hd, Rectangle ob, int cpx, int cpy) {
        if (hd == 8) {
            s.ax = cpx;
            s.ay = cpy;
            return;
        }
        if (hd == 9) {
            s.bx = cpx;
            s.by = cpy;
            return;
        }
        Rectangle nb = new Rectangle(ob);
        if (hd == 0 || hd == 6 || hd == 7) {
            int oldRight = nb.x + nb.width;
            nb.x = Math.min(cpx, oldRight - 4);
            nb.width = oldRight - nb.x;
        }
        if (hd == 2 || hd == 3 || hd == 4) nb.width = Math.max(cpx - nb.x, 4);
        if (hd == 0 || hd == 1 || hd == 2) {
            int oldBottom = nb.y + nb.height;
            nb.y = Math.min(cpy, oldBottom - 4);
            nb.height = oldBottom - nb.y;
        }
        if (hd == 4 || hd == 5 || hd == 6) nb.height = Math.max(cpy - nb.y, 4);
        reshape(s, ob, nb);
    }

    /** 选中对象应用新颜色（无选中为空操作）。 */
    static void applyColor(Editor ed, int argb) {
        if (ed.selected == null) return;
        ed.beginOp();
        ed.selected.color = argb;
    }

    /** 选中对象应用新线宽档（文字=字号档；无选中为空操作）。 */
    static void applyWidth(Editor ed, int idx) {
        if (ed.selected == null) return;
        ed.beginOp();
        if (ed.selected.tool == Tool.Text) ed.selected.fontSize = fontSizeFor(idx);
        else ed.selected.penW = penWidth(idx);
    }

    /** 选中态绘制（sc=当前视图缩放，控制点/虚线保持屏幕恒定大小）。 */
    static void drawSelection(Graphics2D g, Shape s, float sc) {
        float inv = 1f / Math.max(0.05f, sc);
        float hs = 7f * inv, dash = 5f * inv;
        Rectangle b = bounds(s);
        boolean lineLike = s.tool == Tool.Line || s.tool == Tool.Arrow;
        g.setColor(new Color(37, 99, 235, 235));
        g.setStroke(new BasicStroke(1.3f * inv, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f * inv, new float[]{dash, dash * 0.8f}, 0f));
        if (!lineLike)
            g.draw(new Rectangle2D.Float(b.x - 3 * inv, b.y - 3 * inv,
                    b.width + 6 * inv, b.height + 6 * inv));
        else
            g.draw(new java.awt.geom.Line2D.Float(s.ax, s.ay, s.bx, s.by));
        float r = hs / 2f;
        if (lineLike) {
            fillHandle(g, s.ax - r, s.ay - r, hs, hs, inv);
            fillHandle(g, s.bx - r, s.by - r, hs, hs, inv);
            return;
        }
        int cx = b.x + b.width / 2, cy = b.y + b.height / 2;
        int[] hx = {b.x, cx, b.x + b.width, b.x + b.width, b.x + b.width, cx, b.x, b.x};
        int[] hy = {b.y, b.y, b.y, cy, b.y + b.height, b.y + b.height, b.y + b.height, cy};
        for (int i = 0; i < 8; i++)
            fillHandle(g, hx[i] - r, hy[i] - r, hs, hs, inv);
    }

    private static void fillHandle(Graphics2D g, float x, float y, float w, float h, float inv) {
        g.setColor(Color.WHITE);
        g.fill(new Rectangle2D.Float(x, y, w, h));
        g.setColor(new Color(37, 99, 235, 255));
        g.setStroke(new BasicStroke(1.1f * inv));
        g.draw(new Rectangle2D.Float(x, y, w, h));
    }

    /** 基数样条近似：经中点的二次贝塞尔平滑（对应 GDI+ DrawCurve 观感）。 */
    static GeneralPath curve(float[][] pts) {
        GeneralPath p = new GeneralPath();
        p.moveTo(pts[0][0], pts[0][1]);
        for (int i = 1; i < pts.length - 1; i++) {
            float mx = (pts[i][0] + pts[i + 1][0]) / 2f, my = (pts[i][1] + pts[i + 1][1]) / 2f;
            p.quadTo(pts[i][0], pts[i][1], mx, my);
        }
        p.lineTo(pts[pts.length - 1][0], pts[pts.length - 1][1]);
        return p;
    }

    static void drawShapes(Graphics2D g, ArrayList<Shape> v, BufferedImage base, int offX, int offY) {
        for (Shape s : v) drawShape(g, s, base, offX, offY);
    }

    static void drawShape(Graphics2D g, Shape s, BufferedImage base, int offX, int offY) {
        Color c = new Color(s.color, true);
        g.setStroke(new BasicStroke(s.penW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int x1 = s.ax, y1 = s.ay, x2 = s.bx, y2 = s.by;
        int rx = Math.min(x1, x2), ry = Math.min(y1, y2);
        int rw = Math.abs(x2 - x1), rh = Math.abs(y2 - y1);
        switch (s.tool) {
            case Rect:
                g.setColor(c);
                g.draw(new Rectangle2D.Float(rx, ry, rw, rh));
                break;
            case Ellipse:
                g.setColor(c);
                g.draw(new Ellipse2D.Float(rx, ry, rw, rh));
                break;
            case Line:
                g.setColor(c);
                g.draw(new java.awt.geom.Line2D.Float(x1, y1, x2, y2));
                break;
            case Arrow: {
                g.setColor(c);
                g.draw(new java.awt.geom.Line2D.Float(x1, y1, x2, y2));
                double ang = Math.atan2(y2 - y1, x2 - x1);
                float head = Math.max(10f, s.penW * 3.2f);
                float lx = (float) (x2 - head * Math.cos(ang - 0.42));
                float ly = (float) (y2 - head * Math.sin(ang - 0.42));
                float rx2 = (float) (x2 - head * Math.cos(ang + 0.42));
                float ry2 = (float) (y2 - head * Math.sin(ang + 0.42));
                GeneralPath p = new GeneralPath();
                p.moveTo(lx, ly);
                p.lineTo(x2, y2);
                p.lineTo(rx2, ry2);
                p.closePath();
                g.fill(p);
                break;
            }
            case Pen: {
                if (s.pts == null || s.pts.isEmpty()) break;
                g.setColor(c);
                if (s.pts.size() == 1) {
                    int[] q = s.pts.get(0);
                    g.fill(new Ellipse2D.Float(q[0] - s.penW / 2f, q[1] - s.penW / 2f, s.penW, s.penW));
                } else {
                    float[][] pf = new float[s.pts.size()][2];
                    for (int i = 0; i < s.pts.size(); i++) {
                        pf[i][0] = s.pts.get(i)[0];
                        pf[i][1] = s.pts.get(i)[1];
                    }
                    g.draw(curve(pf));
                }
                break;
            }
            case Text: {
                if (s.text.isEmpty()) break;
                g.setColor(c);
                g.setFont(new Font(Tb.UI_FAMILY, Font.PLAIN, s.fontSize));
                g.drawString(s.text, s.ax, s.ay - s.fontSize * 0.8f);
                break;
            }
            case Mosaic: {
                if (base == null) break;
                int bw = base.getWidth(), bh = base.getHeight();
                if (s.mStyle == 2) {
                    g.setColor(new Color(10, 10, 10));
                    g.fill(new Rectangle2D.Float(rx, ry, rw, rh));
                    break;
                }
                int blk = Math.max(4, s.mSize);
                int[][] off5 = null;
                if (s.mStyle == 1)
                    off5 = new int[][]{{1, 1}, {3, 1}, {2, 2}, {1, 3}, {3, 3}};
                for (int y = ry; y < ry + rh; y += blk) {
                    for (int x = rx; x < rx + rw; x += blk) {
                        int w = Math.min(blk, rx + rw - x);
                        int h = Math.min(blk, ry + rh - y);
                        if (w <= 0 || h <= 0) continue;
                        int pc;
                        if (s.mStyle == 1) {   // 模糊：块内 5 点均值
                            int r = 0, gg = 0, bb = 0;
                            for (int[] o : off5) {
                                int sx = Math.max(0, Math.min(bw - 1, offX + x + w * o[0] / 4));
                                int sy = Math.max(0, Math.min(bh - 1, offY + y + h * o[1] / 4));
                                int c2 = base.getRGB(sx, sy);
                                r += (c2 >> 16) & 0xFF;
                                gg += (c2 >> 8) & 0xFF;
                                bb += c2 & 0xFF;
                            }
                            pc = 0xFF000000 | ((r / 5) << 16) | ((gg / 5) << 8) | (bb / 5);
                        } else {               // 方格：块中心单像素
                            int sx = Math.max(0, Math.min(bw - 1, offX + x + w / 2));
                            int sy = Math.max(0, Math.min(bh - 1, offY + y + h / 2));
                            pc = base.getRGB(sx, sy) | 0xFF000000;
                        }
                        g.setColor(new Color(pc, true));
                        g.fill(new Rectangle2D.Float(x, y, w, h));
                    }
                }
                break;
            }
            case Highlight: {
                g.setColor(new Color((s.color >> 16) & 0xFF, (s.color >> 8) & 0xFF, s.color & 0xFF, 80));
                g.fill(new Rectangle2D.Float(rx, ry, rw, rh));
                break;
            }
            default:
                break;
        }
    }
}
