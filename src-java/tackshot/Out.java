package tackshot;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

/** 文件保存：PNG/JPEG（质量可配）、时间戳命名、冲突后缀、另存为对话框。 */
final class Out {
    private Out() {}

    static String nowStamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
    }

    static String defaultSaveDir() {
        try {
            String dir = Nat.picturesDir() + "\\TackShot";
            new File(dir).mkdirs();
            return dir;
        } catch (Throwable t) {
            return Main.exeDir;
        }
    }

    static String buildSavePath(String dir, String ext) {
        for (int i = 0; i < 100; i++) {
            String name = i == 0 ? nowStamp() : nowStamp() + "_" + i;
            String p = dir + "\\" + name + "." + ext;
            if (!new File(p).exists()) return p;
        }
        return dir + "\\" + nowStamp() + "." + ext;
    }

    static boolean save(BufferedImage img, String path, boolean jpeg, int jpegQuality) {
        try {
            File f = new File(path);
            if (jpeg) {
                java.util.Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
                if (!it.hasNext()) return false;
                ImageWriter iw = it.next();
                try (ImageOutputStream os = ImageIO.createImageOutputStream(f)) {
                    iw.setOutput(os);
                    ImageWriteParam pm = iw.getDefaultWriteParam();
                    pm.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    pm.setCompressionQuality(Math.max(0.05f, Math.min(1f, jpegQuality / 100f)));
                    iw.write(null, new IIOImage(img, null, null), pm);
                } finally {
                    iw.dispose();
                }
                return true;
            }
            return ImageIO.write(img, "png", f);
        } catch (Exception e) {
            return false;
        }
    }

    static void saveAsDialog(Window parent, BufferedImage img) {
        boolean jpeg = Main.cfg.format.equals("jpeg");
        String dir = Main.cfg.outputDir.isEmpty() ? defaultSaveDir() : Main.cfg.outputDir;
        JFileChooser fc = new JFileChooser(dir);
        fc.setFileFilter(new FileNameExtensionFilter(jpeg ? "JPEG 图像" : "PNG 图像",
                jpeg ? "jpg" : "png"));
        fc.setSelectedFile(new File(dir, nowStamp() + (jpeg ? ".jpg" : ".png")));
        if (fc.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            if (!f.getName().contains("."))
                f = new File(f.getParentFile(), f.getName() + (jpeg ? ".jpg" : ".png"));
            if (save(img, f.getPath(), jpeg, Main.cfg.jpegQuality))
                Main.balloon("钉图 TackShot", "已保存：" + f.getPath());
            else
                Main.balloon("钉图 TackShot", "保存失败：无法写入目标文件");
        }
    }
}
