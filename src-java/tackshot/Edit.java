package tackshot;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;

/** 标注对象模型与绘制（截图编辑与贴图就地编辑共用）。 */
final class Edit {
    private Edit() {}

    enum Tool { None, Rect, Ellipse, Line, Arrow, Pen, Text, Mosaic, Highlight }

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
        ArrayList<Shape> shapes = new ArrayList<>(), redo = new ArrayList<>();
        Tool cur = Tool.None;
        int color = 0xFFEF4444;
        int widthIdx = 1;
        int mosaicStyle = 0, mosaicSize = 20;
        Shape draft = new Shape();

        void push(Shape s) { shapes.add(s); redo.clear(); }
        boolean undo() {
            if (shapes.isEmpty()) return false;
            redo.add(shapes.remove(shapes.size() - 1));
            return true;
        }
        boolean redoOp() {
            if (redo.isEmpty()) return false;
            shapes.add(redo.remove(redo.size() - 1));
            return true;
        }
    }

    static final int[] PEN_WIDTHS = {2, 4, 7};
    static final int[] FONT_SIZES = {18, 26, 36};

    static int penWidth(int idx) { return PEN_WIDTHS[((idx % 3) + 3) % 3]; }
    static int fontSizeFor(int idx) { return FONT_SIZES[((idx % 3) + 3) % 3]; }

    static Tool toolFromKey(int keyCode) {
        switch (keyCode) {
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
