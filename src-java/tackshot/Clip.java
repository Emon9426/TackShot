package tackshot;

import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.Pointer;

import java.awt.Window;
import java.awt.image.BufferedImage;

/** 剪贴板：JNA 直写 CF_DIB（bottom-up 32bpp、alpha 强制 0xFF），与 C++ 版粘贴兼容性一致。 */
final class Clip {
    /** 标准剪贴板格式：CF_BITMAP=2、CF_DIB=8（曾误写 2，导致外部程序无法粘贴）。 */
    static final int CF_DIB = 8;
    private static final int GMEM_MOVEABLE = 2;

    private Clip() {}

    /** OpenClipboard 可能被剪贴板管理器等短暂占用，短重试提高成功率。 */
    private static boolean openClipboard(HWND owner) {
        for (int i = 0; i < 10; i++) {
            if (Nat.U32.I.OpenClipboard(owner)) return true;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    static boolean toClipboard(Window owner, BufferedImage img) {
        if (img == null) return false;
        int w = img.getWidth(), h = img.getHeight();
        if (w <= 0 || h <= 0) return false;
        long total = 40L + (long) w * h * 4;
        if (total > Integer.MAX_VALUE - 16) return false;
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        HANDLE hg = Nat.K32.I.GlobalAlloc(GMEM_MOVEABLE, total);
        if (hg == null) return false;
        Pointer p = Nat.K32.I.GlobalLock(hg);
        if (p == null) {
            Nat.K32.I.GlobalFree(hg);
            return false;
        }
        p.setInt(0, 40);            // biSize
        p.setInt(4, w);             // biWidth
        p.setInt(8, h);             // biHeight（bottom-up）
        p.setShort(12, (short) 1);  // biPlanes
        p.setShort(14, (short) 32); // biBitCount
        p.setInt(16, 0);            // BI_RGB
        p.setInt(20, w * h * 4);    // biSizeImage
        p.setMemory(24, 16, (byte) 0);
        byte[] row = new byte[w * 4];
        int off = 40;
        for (int y = 0; y < h; y++) {
            int src = (h - 1 - y) * w;
            for (int x = 0; x < w; x++) {
                int c = rgb[src + x];
                row[x * 4] = (byte) (c & 0xFF);
                row[x * 4 + 1] = (byte) ((c >> 8) & 0xFF);
                row[x * 4 + 2] = (byte) ((c >> 16) & 0xFF);
                row[x * 4 + 3] = (byte) 0xFF;
            }
            p.write(off, row, 0, row.length);
            off += row.length;
        }
        Nat.K32.I.GlobalUnlock(hg);
        HWND oh = owner == null ? null : Nat.hwndOf(owner);
        if (!openClipboard(oh)) {
            Nat.K32.I.GlobalFree(hg);
            return false;
        }
        Nat.U32.I.EmptyClipboard();
        HANDLE res = Nat.U32.I.SetClipboardData(CF_DIB, hg);
        Nat.U32.I.CloseClipboard();
        return res != null;
    }

    static BufferedImage fromClipboard() {
        if (!Nat.U32.I.IsClipboardFormatAvailable(CF_DIB)) return null;
        if (!Nat.U32.I.OpenClipboard(null)) return null;
        try {
            HANDLE h = Nat.U32.I.GetClipboardData(CF_DIB);
            if (h == null) return null;
            Pointer p = Nat.K32.I.GlobalLock(h);
            if (p == null) return null;
            try {
                int biSize = p.getInt(0);
                int w = p.getInt(4);
                int hRaw = p.getInt(8);
                boolean bottomUp = hRaw > 0;
                int hh = Math.abs(hRaw);
                int bpp = p.getShort(14) & 0xFFFF;
                int compression = p.getInt(16);
                int clrUsed = p.getInt(32);
                if (w <= 0 || hh <= 0 || w >= 32768 || hh >= 32768
                        || (bpp != 24 && bpp != 32) || compression != 0) return null;
                int body = biSize + (clrUsed != 0 ? clrUsed * 4 : 0);
                int bpx = bpp / 8;
                BufferedImage out = new BufferedImage(w, hh, BufferedImage.TYPE_INT_RGB);
                int[] rgb = new int[w * hh];
                byte[] rowBuf = new byte[w * bpx];
                for (int y = 0; y < hh; y++) {
                    int sy = bottomUp ? (hh - 1 - y) : y;
                    p.read(body + (long) sy * w * bpx, rowBuf, 0, w * bpx);
                    int drow = y * w;
                    for (int x = 0; x < w; x++) {
                        int b = rowBuf[x * bpx] & 0xFF;
                        int gg = rowBuf[x * bpx + 1] & 0xFF;
                        int r = rowBuf[x * bpx + 2] & 0xFF;
                        rgb[drow + x] = 0xFF000000 | (r << 16) | (gg << 8) | b;
                    }
                }
                out.setRGB(0, 0, w, hh, rgb, 0, w);
                return out;
            } finally {
                Nat.K32.I.GlobalUnlock(h);
            }
        } finally {
            Nat.U32.I.CloseClipboard();
        }
    }
}
