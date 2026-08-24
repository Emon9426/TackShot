package tackshot;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import java.awt.Color;
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
 * 设置窗体（AI 版 · D2 三标签页）：热键与目录 / ✨ AI / 关于。
 * AI 页（FR-8.1）：启用开关、PAT 录入（密码框）/测试连接/清除、模型三档、翻译目标语言、发送前确认；
 * 令牌仅存 Windows 凭据管理器（TokenVault），不落 config-ai.json。
 */
public final class Settings {
    private Settings() {}

    public static void showDialog() {
        final JDialog dlg = new JDialog(Main.hidden, "设置 · 钉图 TackShot（AI 版）", true);
        Font plain = new Font(Tb.UI_FAMILY, Font.PLAIN, 13);
        Font bold = new Font(Tb.UI_FAMILY, Font.BOLD, 13);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(plain);
        tabs.addTab("热键与目录", buildHotkeyTab(dlg, plain, bold));
        tabs.addTab("✨ AI", buildAiTab(dlg, plain, bold));
        tabs.addTab("关于", buildAboutTab(plain));
        tabs.setSelectedIndex(Main.aiTestMode ? 1 : 0);   // /aitest 直达 AI 页

        JPanel root = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(8, 8, 8, 8);
        gc.fill = GridBagConstraints.BOTH;
        gc.weightx = 1;
        gc.weighty = 1;
        root.add(tabs, gc);
        dlg.setContentPane(root);
        dlg.pack();
        Dimension sz = dlg.getPreferredSize();
        dlg.setMinimumSize(new Dimension(Math.max(600, sz.width), sz.height));
        dlg.setLocationRelativeTo(null);
        dlg.setVisible(true);
    }

    // ---------------- 页 1：热键与目录（V2.1 原内容平移） ----------------

    private static JPanel buildHotkeyTab(JDialog dlg, Font plain, Font bold) {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 10, 6, 10);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;

        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 3;
        JLabel t1 = new JLabel("全局热键（点击输入框后按下组合键，Esc 取消；须含 Ctrl/Alt/Win）");
        t1.setFont(bold);
        p.add(t1, gc);

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
            p.add(lb, gc);
            gc.gridx = 1;
            gc.weightx = 1;
            p.add(hots[i], gc);
            gc.gridx = 2;
            gc.weightx = 0;
            p.add(new JLabel(" "), gc);
            gc.gridx = 0;
        }

        gc.gridy++;
        gc.gridwidth = 3;
        JLabel t2 = new JLabel("默认图片保存目录（空 = 图片库 \\TackShot）");
        t2.setFont(bold);
        p.add(t2, gc);
        gc.gridy++;
        gc.gridwidth = 1;
        final JTextField dir = new JTextField(displayDir());
        dir.setEditable(false);
        dir.setFont(plain);
        final String[] chosen = {Main.cfg.outputDir};
        gc.gridx = 0;
        gc.weightx = 1;
        p.add(dir, gc);
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
        p.add(browse, gc);
        gc.gridx = 2;
        JButton reset = new JButton("恢复默认");
        reset.setFont(plain);
        reset.addActionListener(e -> {
            chosen[0] = "";
            dir.setText(displayDir());
        });
        p.add(reset, gc);

        gc.gridx = 0;
        gc.gridy++;
        gc.gridwidth = 3;
        final JLabel hint = new JLabel(" ");
        hint.setFont(plain);
        p.add(hint, gc);
        gc.gridy++;
        JPanel btns = new JPanel();
        JButton ok = new JButton("保存并关闭");
        JButton cancel = new JButton("取消");
        ok.setFont(plain);
        cancel.setFont(plain);
        btns.add(ok);
        btns.add(cancel);
        p.add(btns, gc);

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
        return p;
    }

    // ---------------- 页 2：AI（FR-8.1） ----------------

    private static JPanel buildAiTab(JDialog dlg, Font plain, Font bold) {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(7, 10, 7, 10);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;

        boolean hadToken = tackshot.ai.AiService.hasToken();

        // 启用开关
        JCheckBox enable = new JCheckBox("启用 AI 功能（默认关闭；未配置令牌时右键 AI 项会给出引导）", Main.cfg.aiEnabled);
        enable.setFont(plain);
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 3;
        p.add(enable, gc);

        // 前置条件提示 + 令牌
        gc.gridy++;
        gc.gridwidth = 3;
        JLabel pre = new JLabel("<html><body style='width:540px'>前置条件：本机需已安装 <b>GitHub Copilot CLI</b>"
                + "（winget install GitHub.Copilot 或 npm install -g @github/copilot，需 Node 22+）——"
                + "SDK 经本机 copilot 命令连接模型，未安装时测试连接会失败。</body></html>");
        pre.setFont(new Font(Tb.UI_FAMILY, Font.PLAIN, 12));
        pre.setForeground(new Color(0xb4, 0x53, 0x09));
        p.add(pre, gc);
        gc.gridy++;
        gc.gridwidth = 3;
        p.add(label("GitHub 访问令牌（细粒度 PAT，github_pat_ 开头）", bold), gc);
        gc.gridy++;
        gc.gridwidth = 1;
        gc.weightx = 1;
        JPasswordField token = new JPasswordField(28);
        token.setFont(plain);
        token.setEchoChar('•');
        p.add(token, gc);
        gc.weightx = 0;
        final boolean[] clearReq = {false};
        final boolean[] showPlain = {false};
        JButton test = new JButton("测试连接");
        JButton showBtn = new JButton("显示");
        JButton clear = new JButton("清除");
        test.setFont(plain);
        showBtn.setFont(plain);
        clear.setFont(plain);
        gc.gridx = 1;
        p.add(test, gc);
        gc.gridx = 2;
        JPanel tokBtns = new JPanel();
        tokBtns.add(showBtn);
        tokBtns.add(clear);
        p.add(tokBtns, gc);
        showBtn.addActionListener(e -> {
            showPlain[0] = !showPlain[0];
            token.setEchoChar(showPlain[0] ? (char) 0 : '•');
            showBtn.setText(showPlain[0] ? "隐藏" : "显示");
        });
        clear.addActionListener(e -> {
            token.setText("");
            clearReq[0] = true;
        });

        // 测试结果行
        gc.gridx = 0;
        gc.gridy++;
        gc.gridwidth = 3;
        final JLabel testOut = new JLabel(hadToken ? "已保存令牌（点击「测试连接」验证）" : "未配置令牌");
        testOut.setFont(plain);
        testOut.setForeground(hadToken ? new Color(0x16, 0xa3, 0x4a) : new Color(0xb4, 0x53, 0x09));
        p.add(testOut, gc);
        test.addActionListener(e -> {
            String t = new String(token.getPassword()).trim();
            if (!t.isEmpty()) {
                if (!tackshot.ai.AiService.saveToken(t)) {
                    testOut.setText("令牌保存失败（凭据管理器不可用？）");
                    testOut.setForeground(new Color(0xdc, 0x26, 0x26));
                    return;
                }
                clearReq[0] = false;
            } else if (!tackshot.ai.AiService.hasToken()) {
                testOut.setText("请先粘贴 GitHub 访问令牌");
                testOut.setForeground(new Color(0xb4, 0x53, 0x09));
                return;
            }
            test.setEnabled(false);
            testOut.setText("测试中（首次将启动/下载 Copilot CLI，耗时可能较长）…");
            testOut.setForeground(new Color(0x5a, 0x64, 0x74));
            new Thread(() -> {
                String err = tackshot.ai.AiService.testConnection();
                javax.swing.SwingUtilities.invokeLater(() -> {
                    test.setEnabled(true);
                    if (err == null) {
                        testOut.setText("✓ " + tackshot.ai.AiService.okSummary());
                        testOut.setForeground(new Color(0x16, 0xa3, 0x4a));
                        Log.write("AI 测试连接：成功");
                    } else {
                        testOut.setText("✗ " + err);
                        testOut.setForeground(new Color(0xdc, 0x26, 0x26));
                        Log.write("AI 测试连接：失败 " + err);
                    }
                });
            }, "ai-test-conn").start();
        });

        // 模型档位
        gc.gridy++;
        gc.gridwidth = 3;
        p.add(label("默认模型档位", bold), gc);
        gc.gridy++;
        gc.gridwidth = 3;
        JPanel tiers = new JPanel();
        JRadioButton fast = radio("快速 · gpt-4o-mini（识别/翻译，最省额度）");
        JRadioButton balanced = radio("均衡 · gpt-4o（复杂版面）");
        JRadioButton deep = radio("深度 · claude-sonnet-4");
        String cur = Main.cfg.aiTier;
        fast.setSelected(!cur.equals("balanced") && !cur.equals("deep"));
        balanced.setSelected(cur.equals("balanced"));
        deep.setSelected(cur.equals("deep"));
        ButtonGroup tg = new ButtonGroup();
        tg.add(fast);
        tg.add(balanced);
        tg.add(deep);
        tiers.add(fast);
        tiers.add(balanced);
        tiers.add(deep);
        p.add(tiers, gc);

        // 翻译目标语言
        gc.gridy++;
        gc.gridwidth = 1;
        p.add(label("翻译目标语言", plain), gc);
        gc.gridx = 1;
        JComboBox<String> lang = new JComboBox<>(new String[]{"中文", "English", "日本語", "한국어"});
        lang.setSelectedItem(Main.cfg.aiTranslateTo);
        lang.setFont(plain);
        lang.setEditable(true);
        p.add(lang, gc);
        gc.gridx = 0;

        // 隐私
        gc.gridy++;
        gc.gridwidth = 3;
        JCheckBox confirm = new JCheckBox("每次发送前确认（显示将使用的模型）", Main.cfg.aiConfirm);
        confirm.setFont(plain);
        p.add(confirm, gc);

        // 隐私说明
        gc.gridy++;
        gc.gridwidth = 3;
        JLabel privacy = new JLabel("<html><body style='width:540px'>"
                + "🔒 令牌仅保存在本机 Windows 凭据管理器（随 Windows 账户加密），不写入配置文件、不外传。<br>"
                + "启用云端 AI 后，截图内容将发送至 GitHub 及其模型供应商（OpenAI / Anthropic 等）处理——"
                + "请勿对涉密截图使用云端功能。用量按 token 计入你的 Copilot 订阅（AI Credits）。<br>"
                + "获取令牌：github.com → Settings → Developer settings → Personal access tokens → Fine-grained tokens。</body></html>");
        privacy.setFont(new Font(Tb.UI_FAMILY, Font.PLAIN, 12));
        privacy.setForeground(new Color(0x5a, 0x64, 0x74));
        privacy.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createDashedBorder(new Color(0xcb, 0xd5, 0xe1)),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        p.add(privacy, gc);

        // 保存
        gc.gridy++;
        gc.gridwidth = 3;
        JPanel btns = new JPanel();
        JButton ok = new JButton("保存并关闭");
        JButton cancel = new JButton("取消");
        ok.setFont(plain);
        cancel.setFont(plain);
        btns.add(ok);
        btns.add(cancel);
        p.add(btns, gc);

        ok.addActionListener(e -> {
            Main.cfg.aiEnabled = enable.isSelected();
            Main.cfg.aiTier = balanced.isSelected() ? "balanced" : (deep.isSelected() ? "deep" : "fast");
            Object sel = lang.getSelectedItem();
            Main.cfg.aiTranslateTo = sel == null ? "中文" : sel.toString();
            Main.cfg.aiConfirm = confirm.isSelected();
            String t = new String(token.getPassword()).trim();
            if (clearReq[0]) {
                tackshot.ai.AiService.deleteToken();
                Log.write("AI：令牌已从凭据管理器清除");
            } else if (!t.isEmpty()) {
                if (!tackshot.ai.AiService.saveToken(t))
                    Log.write("AI：令牌写入凭据管理器失败");
            }
            Main.cfg.save();
            Log.write("AI 设置已保存：启用=" + Main.cfg.aiEnabled + "，档位=" + Main.cfg.aiTier
                    + "，目标语言=" + Main.cfg.aiTranslateTo + "，发送前确认=" + Main.cfg.aiConfirm);
            dlg.dispose();
        });
        cancel.addActionListener(e -> dlg.dispose());
        return p;
    }

    private static JLabel label(String text, Font f) {
        JLabel l = new JLabel(text);
        l.setFont(f);
        return l;
    }

    private static JRadioButton radio(String text) {
        JRadioButton r = new JRadioButton(text);
        r.setFont(new Font(Tb.UI_FAMILY, Font.PLAIN, 13));
        return r;
    }

    // ---------------- 页 3：关于 ----------------

    private static JPanel buildAboutTab(Font plain) {
        JPanel p = new JPanel(new GridBagLayout());
        JLabel l = new JLabel("<html><body style='width:520px;padding:8px'>"
                + "<b>" + Main.VERSION + "</b><br><br>"
                + "轻量级开源截图 · 贴图工具 · AI 识别<br><br>"
                + "AI 能力经官方 GitHub Copilot SDK（com.github:copilot-sdk-java "
                + tackshot.ai.AiService.sdkVersion() + "）接入；<br>"
                + "需自备 GitHub Copilot 订阅与访问令牌；令牌仅存本机凭据管理器。<br><br>"
                + "许可证：MIT（主程序）；SDK 及依赖许可证见 THIRD-PARTY-NOTICES.txt。<br><br>"
                + "本 AI 版与无 AI 主版本相互独立，可同时安装（配置/日志/自启互不干扰）。</body></html>");
        l.setFont(plain);
        p.add(l, new GridBagConstraints());
        return p;
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
