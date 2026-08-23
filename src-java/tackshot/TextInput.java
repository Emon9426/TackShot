package tackshot;

import javax.swing.*;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.Color;
import java.awt.Font;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

/** 文字输入弹窗（对应 C++ 顶层 EDIT）：Enter 提交 / Esc 取消 / 失焦提交；Swing 天然支持 IME。 */
final class TextInput {
    private static JDialog dlg;
    private static JTextField fld;
    private static Consumer<String> onCommit;
    private static boolean committing;

    private TextInput() {}

    static boolean active() { return dlg != null; }

    static void cancel() { close(); }

    private static void close() {
        if (dlg != null) {
            JDialog d = dlg;
            Window owner = d.getOwner();
            dlg = null;
            onCommit = null;
            d.dispose();
            if (owner != null && owner.isVisible()) owner.requestFocus();
        }
    }

    static void start(Window owner, Point sp, int fontSizePx, int color, Consumer<String> commit) {
        cancel();
        int h = fontSizePx + 14, w = 220;
        onCommit = commit;
        dlg = new JDialog(owner, java.awt.Dialog.ModalityType.MODELESS);
        dlg.setUndecorated(true);
        dlg.setAlwaysOnTop(true);
        fld = new JTextField();
        fld.setFont(new Font(Tb.UI_FAMILY, Font.PLAIN, fontSizePx));
        fld.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(113, 113, 113)),
                BorderFactory.createEmptyBorder(1, 4, 1, 4)));
        ((AbstractDocument) fld.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void insertString(FilterBypass fb, int offs, String str, AttributeSet a)
                    throws BadLocationException {
                if (fb.getDocument().getLength() + str.length() > 500) return;
                fb.insertString(offs, str, a);
            }
            @Override
            public void replace(FilterBypass fb, int offs, int len, String str, AttributeSet a)
                    throws BadLocationException {
                if (fb.getDocument().getLength() - len + str.length() > 500) return;
                fb.replace(offs, len, str, a);
            }
        });
        fld.addActionListener(e -> commit());
        fld.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) { commit(); }
        });
        fld.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) cancel();
            }
        });
        dlg.setContentPane(fld);
        dlg.setSize(w, h);
        dlg.setLocation(sp.x, sp.y - h / 2);
        dlg.setVisible(true);
        Nat.hideFromTaskbar(dlg);
        fld.requestFocusInWindow();
    }

    private static void commit() {
        if (dlg == null || committing) return;
        committing = true;
        try {
            String t = fld.getText();
            Consumer<String> cb = onCommit;
            close();
            if (cb != null && !t.isEmpty()) cb.accept(t);
        } finally {
            committing = false;
        }
    }
}
