package tackshot;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/** 运行时矢量绘制：应用图标（方头图钉）与截图十字光标，无资源文件。 */
final class Icon {
    private Icon() {}

    static BufferedImage appIcon() {
        BufferedImage bi = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new Color(30, 37, 54));
        g.fillOval(1, 1, 30, 30);
        RoundRectangle2D.Float head = new RoundRectangle2D.Float(8.5f, 5.5f, 15f, 10f, 6f, 6f);
        g.setColor(new Color(245, 158, 11));
        g.fill(head);
        g.setStroke(new BasicStroke(2f));
        g.setColor(Color.WHITE);
        g.draw(head);
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(226, 232, 240));
        g.draw(new Line2D.Float(16, 16, 21, 28));
        g.setStroke(new BasicStroke(1.6f));
        g.setColor(new Color(254, 243, 199));
        g.draw(new Line2D.Float(11, 8, 19, 8));
        g.dispose();
        return bi;
    }

    /** 自定义十字光标：白芯黑描边，32px，热点 (16,16)。 */
    static Cursor crossCursor() {
        BufferedImage bi = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        int[][] seg = {{16, 0, 16, 11}, {16, 21, 16, 31}, {0, 16, 11, 16}, {21, 16, 31, 16}};
        g.setStroke(new BasicStroke(5f));
        g.setColor(Color.BLACK);
        for (int[] s : seg) g.draw(new Line2D.Float(s[0], s[1], s[2], s[3]));
        g.setStroke(new BasicStroke(2f));
        g.setColor(Color.WHITE);
        for (int[] s : seg) g.draw(new Line2D.Float(s[0], s[1], s[2], s[3]));
        g.dispose();
        try {
            return Toolkit.getDefaultToolkit().createCustomCursor(bi, new Point(16, 16), "TackShotCross");
        } catch (Throwable t) {
            return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
        }
    }
}
