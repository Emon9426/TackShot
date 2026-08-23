package tackshot;

import com.sun.jna.platform.win32.WinUser.MSG;

import javax.swing.SwingUtilities;
import java.util.function.IntConsumer;

/** 全局热键：守护线程 RegisterHotKey(NULL) + PeekMessageW 轮询 → EDT 回调。 */
final class Hotkeys implements Runnable {
    static final int HK_REGION = 1, HK_FULL = 2, HK_PIN = 3;
    private static final int MOD_NOREPEAT = 0x4000, WM_HOTKEY = 0x0312;

    private final Cfg cfg;
    private final IntConsumer action;
    private volatile boolean running = true;
    private final Thread thread;

    private Hotkeys(Cfg cfg, IntConsumer action) {
        this.cfg = cfg;
        this.action = action;
        this.thread = new Thread(this, "tackshot-hotkeys");
    }

    static Hotkeys start(Cfg cfg, IntConsumer action) {
        Hotkeys h = new Hotkeys(cfg, action);
        h.thread.setDaemon(true);
        h.thread.start();
        return h;
    }

    void shutdown() {
        running = false;
        thread.interrupt();
    }

    @Override
    public void run() {
        int[][] hs = {
                {HK_REGION}, {HK_FULL}, {HK_PIN}};
        String[] keys = {cfg.hotkeyRegion, cfg.hotkeyFull, cfg.hotkeyPin};
        String[] names = {"区域截图", "全屏截图", "贴图"};
        StringBuilder fails = new StringBuilder();
        for (int i = 0; i < hs.length; i++) {
            Cfg.Hot p = Cfg.parseHotkey(keys[i]);
            if (!p.ok || !Nat.U32.I.RegisterHotKey(null, hs[i][0], p.mods | MOD_NOREPEAT, p.vk)) {
                fails.append(names[i]).append("(").append(keys[i]).append(") ");
                Log.write("热键注册失败：" + names[i] + " = " + keys[i]);
            } else {
                Log.write("热键已注册：" + names[i] + " = " + keys[i]);
            }
        }
        if (fails.length() > 0) {
            String f = fails.toString();
            SwingUtilities.invokeLater(() ->
                    Main.balloon("钉图 TackShot", "以下热键被占用，可在 config.json 中修改：" + f));
        }
        MSG msg = new MSG();
        while (running) {
            while (running && Nat.U32.I.PeekMessageW(msg, null, 0, 0, 1)) {
                if (msg.message == WM_HOTKEY) {
                    final int id = msg.wParam.intValue();
                    SwingUtilities.invokeLater(() -> action.accept(id));
                }
            }
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                break;
            }
        }
        Nat.U32.I.UnregisterHotKey(null, HK_REGION);
        Nat.U32.I.UnregisterHotKey(null, HK_FULL);
        Nat.U32.I.UnregisterHotKey(null, HK_PIN);
    }
}
