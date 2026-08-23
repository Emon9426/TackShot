package tackshot;

import com.sun.jna.Callback;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Shell32Util;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinUser.MSG;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JNA 原生绑定与辅助（仅依赖 jna-platform 的结构类型）。 */
final class Nat {
    private Nat() {}

    interface U32 extends StdCallLibrary {
        U32 I = Native.load("user32", U32.class, W32APIOptions.UNICODE_OPTIONS);
        boolean RegisterHotKey(HWND hWnd, int id, int mods, int vk);
        boolean UnregisterHotKey(HWND hWnd, int id);
        boolean PeekMessageW(MSG msg, HWND hWnd, int min, int max, int remove);
        HWND GetForegroundWindow();
        boolean SetForegroundWindow(HWND hWnd);
        HWND SetFocus(HWND hWnd);
        boolean IsWindowVisible(HWND hWnd);
        int GetWindowThreadProcessId(HWND hWnd, int[] lpdwProcessId);
        int GetWindowTextW(HWND hWnd, char[] text, int maxCount);
        int GetWindowLongW(HWND hWnd, int nIndex);
        int SetWindowLongW(HWND hWnd, int nIndex, int dwNewLong);
        boolean EnumWindows(EnumCb proc, Pointer lParam);
        HWND GetShellWindow();
        HWND MonitorFromWindow(HWND hwnd, int flags);
        boolean GetMonitorInfoW(HWND hMonitor, MonInfo info);
        int GetDpiForWindow(HWND hwnd);
        boolean OpenClipboard(HWND hWndNewOwner);
        boolean EmptyClipboard();
        HANDLE SetClipboardData(int uFormat, HANDLE hMem);
        HANDLE GetClipboardData(int uFormat);
        boolean IsClipboardFormatAvailable(int format);
        boolean CloseClipboard();
    }

    interface K32 extends StdCallLibrary {
        K32 I = Native.load("kernel32", K32.class, W32APIOptions.UNICODE_OPTIONS);
        HANDLE GlobalAlloc(int uFlags, long dwBytes);
        Pointer GlobalLock(HANDLE hMem);
        boolean GlobalUnlock(HANDLE hMem);
        HANDLE GlobalFree(HANDLE hMem);
    }

    interface Dwm extends StdCallLibrary {
        Dwm I = Native.load("dwmapi", Dwm.class, W32APIOptions.UNICODE_OPTIONS);
        int DwmGetWindowAttribute(HWND hwnd, int dwAttribute, Pointer pvAttribute, int cbAttribute);
    }

    interface EnumCb extends Callback {
        boolean invoke(HWND hwnd, Pointer lParam);
    }

    @Structure.FieldOrder({"cbSize", "rcMonitor", "rcWork", "dwFlags"})
    public static class MonInfo extends Structure {
        public int cbSize;
        public RECT rcMonitor;
        public RECT rcWork;
        public int dwFlags;
    }

    private static final int GWL_STYLE = -16, GWL_EXSTYLE = -20;
    private static final int WS_CHILD = 0x40000000, WS_EX_TOOLWINDOW = 0x80,
            WS_EX_NOACTIVATE = 0x08000000;
    private static final String RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    static HWND hwndOf(Window w) {
        if (w == null || !w.isDisplayable()) return null;
        long h = Native.getComponentID(w);
        return h == 0 ? null : new HWND(Pointer.createConstant(h));
    }

    /** 隐藏任务栏按钮与 Alt-Tab 项（等价 WS_EX_TOOLWINDOW）。 */
    static void hideFromTaskbar(Window w) {
        try {
            HWND hwnd = hwndOf(w);
            if (hwnd == null) return;
            int ex = U32.I.GetWindowLongW(hwnd, GWL_EXSTYLE);
            U32.I.SetWindowLongW(hwnd, GWL_EXSTYLE, ex | WS_EX_TOOLWINDOW);
        } catch (Throwable ignored) {
        }
    }

    /** AWT 的 SunAwtWindow 原生带 WS_EX_NOACTIVATE：点击永不激活、键盘事件丢失。
     *  清除之，恢复与 C++ 版一致的点击激活语义。 */
    static void makeActivatable(Window w) {
        try {
            HWND hwnd = hwndOf(w);
            if (hwnd == null) return;
            int ex = U32.I.GetWindowLongW(hwnd, GWL_EXSTYLE);
            U32.I.SetWindowLongW(hwnd, GWL_EXSTYLE, ex & ~WS_EX_NOACTIVATE);
        } catch (Throwable ignored) {
        }
    }

    /** 等价 C++ SetForegroundWindow + SetFocus（截图遮罩显示时抢键盘焦点）。 */
    static void forceForeground(Window w) {
        try {
            HWND hwnd = hwndOf(w);
            if (hwnd == null) return;
            U32.I.SetForegroundWindow(hwnd);
            U32.I.SetFocus(hwnd);
        } catch (Throwable ignored) {
        }
    }

    /** UI 缩放系数：窗口 DPI 优先，退化到主屏 DPI（96 基准）。 */
    static float dpiScale(Window w) {
        int dpi = 0;
        try {
            if (w != null) {
                HWND hwnd = hwndOf(w);
                if (hwnd != null) dpi = U32.I.GetDpiForWindow(hwnd);
            }
        } catch (Throwable ignored) {
        }
        if (dpi == 0) dpi = java.awt.Toolkit.getDefaultToolkit().getScreenResolution();
        if (dpi < 96) dpi = 96;
        return dpi / 96f;
    }

    static Pointer getForeground() {
        HWND h = U32.I.GetForegroundWindow();
        return h == null ? null : h.getPointer();
    }

    static void setForeground(Pointer p) {
        if (p != null) U32.I.SetForegroundWindow(new HWND(p));
    }

    static final class SnapWin {
        final Rectangle r;
        final String title;
        SnapWin(Rectangle r, String title) { this.r = r; this.title = title; }
    }

    /** 窗口吸附候选：可见顶层窗口（DWM 无阴影边界），自顶向下 z 序。 */
    static List<SnapWin> enumSnaps(Rectangle vi) {
        List<SnapWin> out = new ArrayList<>();
        long selfPid = Integer.toUnsignedLong(com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentProcessId());
        HWND shell = U32.I.GetShellWindow();
        int[] pid = new int[1];
        Memory cloak = new Memory(4), rect = new Memory(16);
        char[] title = new char[64];
        U32.I.EnumWindows((h, lp) -> {
            if (!U32.I.IsWindowVisible(h)) return true;
            U32.I.GetWindowThreadProcessId(h, pid);
            if (Integer.toUnsignedLong(pid[0]) == selfPid) return true;
            if ((U32.I.GetWindowLongW(h, GWL_EXSTYLE) & WS_EX_TOOLWINDOW) != 0) return true;
            if ((U32.I.GetWindowLongW(h, GWL_STYLE) & WS_CHILD) != 0) return true;
            if (shell != null && shell.equals(h)) return true;
            if (Dwm.I.DwmGetWindowAttribute(h, 14, cloak, 4) == 0 && cloak.getInt(0) != 0) return true;
            int l = 0, t = 0, r = 0, b = 0;
            if (Dwm.I.DwmGetWindowAttribute(h, 9, rect, 16) == 0) {
                l = rect.getInt(0); t = rect.getInt(4); r = rect.getInt(8); b = rect.getInt(12);
            }
            if (r - l < 40 || b - t < 40) return true;
            if (l >= vi.x + vi.width || t >= vi.y + vi.height || r <= vi.x || b <= vi.y) return true;
            Arrays.fill(title, (char) 0);
            U32.I.GetWindowTextW(h, title, 64);
            int e = 0;
            while (e < title.length && title[e] != 0) e++;
            out.add(new SnapWin(new Rectangle(l, t, r - l, b - t), new String(title, 0, e)));
            return true;
        }, null);
        return out;
    }

    /** 前台窗口所在显示器的工作区（与 C++ MonitorFromWindow(GetForegroundWindow) 一致）。 */
    static Rectangle workArea() {
        try {
            HWND mon = U32.I.MonitorFromWindow(U32.I.GetForegroundWindow(), 1 /*MONITOR_DEFAULTTONEAREST*/);
            if (mon != null) {
                MonInfo mi = new MonInfo();
                mi.rcMonitor = new RECT();
                mi.rcWork = new RECT();
                mi.cbSize = mi.size();
                if (U32.I.GetMonitorInfoW(mon, mi))
                    return new Rectangle(mi.rcWork.left, mi.rcWork.top,
                            mi.rcWork.right - mi.rcWork.left, mi.rcWork.bottom - mi.rcWork.top);
            }
        } catch (Throwable ignored) {
        }
        GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        GraphicsConfiguration gc = gd.getDefaultConfiguration();
        return new Rectangle(gc.getBounds());
    }

    static String picturesDir() {
        try {
            return Shell32Util.getKnownFolderPath(new Guid.GUID("{0DDD015D-B06C-45D5-8C4C-F59713854639}"));
        } catch (Throwable t) {
            return Paths.get(System.getProperty("user.home"), "Pictures").toString();
        }
    }

    static boolean autoRunEnabled() {
        try {
            return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, "TackShot");
        } catch (Throwable t) {
            return false;
        }
    }

    static void setAutoRun(boolean on) {
        try {
            if (on) {
                String javaw = Paths.get(System.getProperty("java.home"), "bin", "javaw.exe").toString();
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, "TackShot",
                        "\"" + javaw + "\" -Dsun.java2d.uiScale.enabled=false -Xmx128m -jar \"" + Main.exePath + "\"");
            } else {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, "TackShot");
            }
        } catch (Throwable ignored) {
        }
    }
}
