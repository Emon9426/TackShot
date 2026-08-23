package tackshot;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.function.Consumer;

/** 调试帧转储：TACKSHOT_DEBUG_SHOT=1 时把渲染结果写为 32bpp 顶朝下 BMP（与 C++ 版字节格式一致）。 */
final class BmpDump {
    private BmpDump() {}

    static boolean enabled() {
        return System.getenv("TACKSHOT_DEBUG_SHOT") != null;
    }

    static void write(String path, BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        int rowsz = w * 4;
        byte[] buf = new byte[54 + rowsz * h];
        buf[0] = 'B';
        buf[1] = 'M';
        putInt(buf, 2, buf.length);
        putInt(buf, 10, 54);
        putInt(buf, 14, 40);
        putInt(buf, 18, w);
        putInt(buf, 22, -h);          // top-down
        putShort(buf, 26, 1);
        putShort(buf, 28, 32);
        putInt(buf, 34, rowsz * h);
        for (int i = 0; i < rgb.length; i++) {
            int c = rgb[i];
            buf[54 + i * 4] = (byte) (c & 0xFF);
            buf[54 + i * 4 + 1] = (byte) ((c >> 8) & 0xFF);
            buf[54 + i * 4 + 2] = (byte) ((c >> 16) & 0xFF);
            buf[54 + i * 4 + 3] = (byte) ((c >> 24) & 0xFF);
        }
        try {
            File f = new File(path).getAbsoluteFile();
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(buf);
            }
        } catch (IOException ignored) {
        }
    }

    static void write(String path, Consumer<Graphics2D> renderer, int w, int h) {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        renderer.accept(g);
        g.dispose();
        write(path, bi);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }
}
