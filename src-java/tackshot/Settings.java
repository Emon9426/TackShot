package tackshot;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 设置窗体（FR-7.1/7.2 · V2.1）：热键三项录入式自定义 + 默认图片保存目录。
 * 保存即写回 config.json 并触发热键重注册（冲突由注册结果气泡提示）。
 */
final class Settings {
    private Settings() {}

    static void showDialog() {
        final JDialog dlg = new JDialog(Main.hidden, "设置 · 钉图 TackShot", true);
        dlg.setLayout(new GridBagLayout());
        Font plain = new Font(Tb.UI_FAMILY, Font.PLAIN, 13);
        Font bold = new Font(Tb.UI_FAMILY, Font.BOLD, 13);

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 10, 6, 10);
        gc.fill = GridBagConstraints.HORIZONTAL;

        // ---- 热键 ----
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 3;
        gc.anchor = GridBagConstraints.WEST;
        JLabel t1 = new JLabel("全局热键（点击输入框后按下组合键，Esc 取消；须含 Ctrl/Alt/Win）");
        t1.setFont(bold);
        dlg.add(t1, gc);

        HotField fRegion = new HotField(Main.cfg.hotkeyRegion);
        HotField fFull = new HotField(Main.cfg.hotkeyFull);
        HotField fPin = new HotField(Main.cfg.hotkeyPin);
        HotField[] hots = {fRegion, fFull, fPin};
        String[] names = {"区域截图", "全屏截图", "贴图"};
        for (int i = 0; i < hots.length; i++) {
            gc.gridy++;
            gc.gridwidth = 1;
            gc.weightx = 0;
            JLabel lb = new JLabel(names[i]);
            lb.setFont(plain);
            dlg.add(lb, gc);
            gc.gridx = 1;
            gc.weightx = 1;
            dlg.add(hots[i], gc);
            gc.gridx = 2;
            gc.weightx = 0;
            dlg.add(new JLabel(" "), gc);   // 占位对齐
            gc.gridx = 0;
        }

        // ---- 保存目录 ----
        gc.gridy++;
        gc.gridwidth = 3;
        JLabel t2 = new JLabel("默认图片保存目录（空 = 图片库 \\TackShot）");
        t2.setFont(bold);
        dlg.add(t2, gc);
        gc.gridy++;
        gc.gridwidth = 1;
        final JTextField dir = new JTextField(displayDir());
        dir.setEditable(false);
        dir.setFont(plain);
        final String[] chosen = {Main.cfg.outputDir};   // 空 = 默认
        gc.gridx = 0;
        gc.weightx = 1;
        dlg.add(dir, gc);
        gc.gridx = 1;
        gc.weightx = 0;
        JButton browse = new JButton("浏览…");
        browse.setFont(plain);
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(chosen[0].isEmpty()
                    ? Out.defaultSaveDir() : chosen[0]);
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(dlg) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                if (f != null && f.isDirectory()) {
                    chosen[0] = f.getAbsolutePath();
                    dir.setText(f.getAbsolutePath());
                }
            }
        });
        dlg.add(browse, gc);
        gc.gridx = 2;
        JButton reset = new JButton("恢复默认");
        reset.setFont(plain);
        reset.addActionListener(e -> {
            chosen[0] = "";
            dir.setText(displayDir());
        });
        dlg.add(reset, gc);

        // ---- 状态提示 + 按钮 ----
        gc.gridx = 0;
        gc.gridy++;
        gc.gridwidth = 3;
        final JLabel hint = new JLabel(" ");
        hint.setFont(plain);
        dlg.add(hint, gc);
        gc.gridy++;
        JPanel btns = new JPanel();
        JButton ok = new JButton("保存并关闭");
        JButton cancel = new JButton("取消");
        ok.setFont(plain);
        cancel.setFont(plain);
        btns.add(ok);
        btns.add(cancel);
        dlg.add(btns, gc);

        Runnable dupCheck = () -> {
            Set<String> seen = new LinkedHashSet<>();
            for (HotField f : hots)
                if (!seen.add(f.value)) {
                    hint.setText("存在重复热键：" + f.value);
                    return;
                }
            hint.setText(" ");
        };
        for (HotField f : hots) f.onCaptured = dupCheck;

        ok.addActionListener(e -> {
            for (HotField f : hots)
                if (!f.valid) {
                    hint.setText("热键无效：须为「Ctrl/Alt/Win（+Shift）+ 字母/数字/F1-F12/PrtSc」");
                    return;
                }
            Set<String> seen = new LinkedHashSet<>();
            for (HotField f : hots)
                if (!seen.add(f.value)) {
                    hint.setText("存在重复热键：" + f.value);
                    return;
                }
            Main.cfg.hotkeyRegion = fRegion.value;
            Main.cfg.hotkeyFull = fFull.value;
            Main.cfg.hotkeyPin = fPin.value;
            Main.cfg.outputDir = chosen[0];
            Main.cfg.save();
            if (Main.hotkeys != null) Main.hotkeys.requestRefresh();
            Log.write("设置已保存：热键 " + fRegion.value + " / " + fFull.value + " / " + fPin.value
                    + "，保存目录 " + (chosen[0].isEmpty() ? "默认（" + Out.defaultSaveDir() + "）" : chosen[0]));
            dlg.dispose();
        });
        cancel.addActionListener(e -> dlg.dispose());

        dlg.pack();
        Dimension sz = dlg.getPreferredSize();
        dlg.setMinimumSize(new Dimension(Math.max(560, sz.width), sz.height));
        dlg.setLocationRelativeTo(null);
        dlg.setVisible(true);
    }

    private static String displayDir() {
        return Main.cfg.outputDir.isEmpty() ? Out.defaultSaveDir() : Main.cfg.outputDir;
    }

    /** 热键录入框：点击进入录入态，按键捕获组合键（非录入态仅展示）。 */
    private static final class HotField extends JTextField {
        String value;
        boolean valid = true;
        boolean capturing;
        Runnable onCaptured;

        HotField(String init) {
            super(init == null || init.isEmpty() ? "Ctrl+Alt+A" : init, 14);
            value = getText();
            setEditable(false);
            setFont(new Font(Tb.UI_FAMILY, Font.PLAIN, 13));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new java.awt.Color(113, 113, 122)),
                    BorderFactory.createEmptyBorder(3, 6, 3, 6)));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    startCapture();
                }
            });
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    onKey(e);
                }
            });
        }

        private void startCapture() {
            capturing = true;
            setText("按下组合键…");
        }

        private void onKey(KeyEvent e) {
            if (!capturing) return;
            int code = e.getKeyCode();
            if (code == KeyEvent.VK_ESCAPE) {
                capturing = false;
                setText(value);
                return;
            }
            if (code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT
                    || code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_META)
                return;                       // 等待实际键位
            e.consume();
            capturing = false;
            boolean ctrl = (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0;
            boolean alt = (e.getModifiersEx() & KeyEvent.ALT_DOWN_MASK) != 0;
            boolean shift = (e.getModifiersEx() & KeyEvent.SHIFT_DOWN_MASK) != 0;
            boolean win = (e.getModifiersEx() & KeyEvent.META_DOWN_MASK) != 0;
            String key;
            if (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) key = String.valueOf((char) code);
            else if (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) key = String.valueOf((char) code);
            else if (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12) key = "F" + (code - KeyEvent.VK_F1 + 1);
            else if (code == KeyEvent.VK_PRINTSCREEN) key = "PRTSC";
            else key = null;
            if (key == null || (!ctrl && !alt && !win)) {
                valid = false;                // 格式不合格：保留原值，等待重新录入
                setText("无效，点击重新录入");
                return;
            }
            valid = true;
            StringBuilder b = new StringBuilder();
            if (ctrl) b.append("Ctrl+");
            if (alt) b.append("Alt+");
            if (shift) b.append("Shift+");
            if (win) b.append("Win+");
            b.append(key);
            value = b.toString();
            setText(value);
            if (onCaptured != null) onCaptured.run();
        }
    }
}
