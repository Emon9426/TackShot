package tackshot.ai;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Font;
import java.awt.Window;

/**
 * 贴图右键 AI 菜单（D6：AI 项 + 分隔 + 保留原右键快捷关闭）。
 * Swing 自绘（同 V2.0.4 托盘菜单路线）；公共类＋仅 JDK 类型参数——Pin 为包私有类不能跨包引用。
 */
public final class AiMenu {
    private AiMenu() {}

    /** 在承载窗 (x,y) 处弹出；extract/zh/en/close 由调用方（Pin）注入。 */
    public static void show(Window host, int x, int y,
                            Runnable extract, Runnable zh, Runnable en, Runnable closeAction) {
        float ms = java.awt.Toolkit.getDefaultToolkit().getScreenResolution() / 96f;
        Font f = new Font(tackshot.Main.UI_FONT_FAMILY, Font.PLAIN, (int) (12f * ms + 0.5f));
        JPopupMenu m = new JPopupMenu();
        JMenuItem mi;
        mi = new JMenuItem("✨ 提取文字（OCR）");
        mi.setFont(f);
        mi.addActionListener(e -> extract.run());
        m.add(mi);
        mi = new JMenuItem("✨ 翻译成中文");
        mi.setFont(f);
        mi.addActionListener(e -> zh.run());
        m.add(mi);
        mi = new JMenuItem("✨ 翻译成 English");
        mi.setFont(f);
        mi.addActionListener(e -> en.run());
        m.add(mi);
        m.addSeparator();
        mi = new JMenuItem("AI 设置…");
        mi.setFont(f);
        mi.addActionListener(e -> tackshot.Settings.showDialog());
        m.add(mi);
        m.addSeparator();
        mi = new JMenuItem("关闭贴图  （原右键行为，Esc/双击同效）");
        mi.setFont(f);
        mi.addActionListener(e -> closeAction.run());
        m.add(mi);
        m.pack();
        m.show(host, x, y);
    }
}
