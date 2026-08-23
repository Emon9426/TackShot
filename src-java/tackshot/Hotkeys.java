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
    private volatile boolean refresh;      // 设置窗体保存后置位：热键线程内重注册（须与消息循环同线程）
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

    /** 设置变更后请求按当前 cfg 重新注册（V2.1 设置窗体）。 */
    void requestRefresh() {
        refresh = true;
    }

    private void registerAll() {
        int[] ids = {HK_REGION, HK_FULL, HK_PIN};
        String[] keys = {cfg.hotkeyRegion, cfg.hotkeyFull, cfg.hotkeyPin};
        String[] names = {"区域截图", "全屏截图", "贴图"};
        StringBuilder fails = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            Cfg.Hot p = Cfg.parseHotkey(keys[i]);
            if (!p.ok || !Nat.U32.I.RegisterHotKey(null, ids[i], p.mods | MOD_NOREPEAT, p.vk)) {
                fails.append(names[i]).append("(").append(keys[i]).append(") ");
                Log.write("热键注册失败：" + names[i] + " = " + keys[i]);
            } else {
                Log.write("热键已注册：" + names[i] + " = " + keys[i]);
            }
        }
        if (fails.length() > 0) {
            String f = fails.toString();
            SwingUtilities.invokeLater(() ->
                    Main.balloon("钉图 TackShot", "以下热键被占用，可在设置中修改：" + f));
        }
    }

    private static void unregisterAll() {
        Nat.U32.I.UnregisterHotKey(null, HK_REGION);
        Nat.U32.I.UnregisterHotKey(null, HK_FULL);
        Nat.U32.I.UnregisterHotKey(null, HK_PIN);
    }

    @Override
    public void run() {
        registerAll();
        MSG msg = new MSG();
        while (running) {
            if (refresh) {
                refresh = false;
                unregisterAll();
                registerAll();
            }
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
        unregisterAll();
    }
}
