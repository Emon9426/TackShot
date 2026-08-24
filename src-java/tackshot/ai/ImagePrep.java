package tackshot.ai;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Iterator;

/** 图像预处理：长边 ≤1568px 缩放 + JPEG 85（视觉模型最优分辨率，控制 token 与内存峰值）。 */
final class ImagePrep {
    private ImagePrep() {}

    static final int LONG_EDGE = 1568;

    /** 缩放并编码为 JPEG 字节；异常时返回 null（由调用方降级提示）。 */
    static byte[] toJpegBytes(BufferedImage src) {
        if (src == null) return null;
        try {
            int w = src.getWidth(), h = src.getHeight();
            int longEdge = Math.max(w, h);
            BufferedImage img = src;
            if (longEdge > LONG_EDGE) {
                double f = LONG_EDGE / (double) longEdge;
                int nw = Math.max(1, (int) (w * f)), nh = Math.max(1, (int) (h * f));
                BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(src, 0, 0, nw, nh, null);
                g.dispose();
                img = scaled;
            } else if (img.getType() != BufferedImage.TYPE_INT_RGB) {
                BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                g.drawImage(img, 0, 0, null);
                g.dispose();
                img = rgb;
            }
            Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpg");
            if (!it.hasNext()) return null;
            ImageWriter wr = it.next();
            ImageWriteParam p = wr.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.85f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(96 * 1024);
            wr.setOutput(new MemoryCacheImageOutputStream(bos));
            wr.write(null, new IIOImage(img, null, null), p);
            wr.dispose();
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
