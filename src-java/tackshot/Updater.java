package tackshot;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** FR-6.9 应用自动更新：查 GitHub Releases → 版本比较 → 下载并解压 → 外部 bat 等锁释放→覆盖→重启。
 *  替换由 bat 完成的原因：须先退出本进程再覆盖文件。
 *  全程不调用 PowerShell（解压在 Java 侧完成、等待用单实例锁探测）——杀软对 bat 调 PowerShell 高度敏感。 */
final class Updater {

    private static final String API_LATEST =
            "https://api.github.com/repos/Emon9426/TackShot/releases/latest";
    private static final String RELEASE_PAGE =
            "https://github.com/Emon9426/TackShot/releases/latest";
    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 30_000;
    /** Release Notes 在确认框中的最大展示长度，超长截断（完整内容见发布页）。 */
    private static final int NOTES_LIMIT = 600;

    private Updater() {}

    // ---------------- 入口（托盘菜单「检查更新…」） ----------------

    /** 后台线程查最新 Release，EDT 弹结果；有新版则确认 → 下载 → bat 替换并重启。 */
    static void checkForUpdate() {
        JDialog loading = dialog(null, "检查更新", false);
        JLabel label = new JLabel("正在连接 GitHub 检查更新，请稍候…");
        label.setFont(new Font(Tb.UI_FAMILY, Font.PLAIN, 13));
        JProgressBar busy = new JProgressBar();
        busy.setIndeterminate(true);
        busy.setPreferredSize(new Dimension(300, 18));
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        p.add(label, BorderLayout.CENTER);
        p.add(busy, BorderLayout.SOUTH);
        loading.setContentPane(p);
        loading.pack();
        loading.setLocationRelativeTo(null);
        loading.setVisible(true);

        new Thread(() -> {
            String tag = null, notes = null, zipUrl = null, err = null;
            try {
                String json = httpGet(API_LATEST);
                tag = regexGroup(json, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
                notes = regexGroup(json, "\"body\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
                zipUrl = regexGroup(json, "(?i)\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.zip)\"");
                if (tag == null || zipUrl == null) err = "响应中未找到版本号或安装包（仓库可能尚未上传 zip 资产）";
            } catch (IOException e) {
                err = e.getMessage();
            }
            final String fTag = tag, fNotes = notes, fUrl = zipUrl, fErr = err;
            SwingUtilities.invokeLater(() -> {
                loading.dispose();
                if (fErr != null) {
                    Log.write("FR-6.9 检查更新失败：" + fErr);
                    offerManualDownload("检查更新失败",
                            "无法获取 GitHub 最新版本信息。\n\n原因：" + fErr
                                    + "\n\n可选择打开发布页手动下载。");
                } else {
                    handleRelease(fTag, fNotes, fUrl);
                }
            });
        }, "tackshot-update-check").start();
    }

    // ---------------- 版本比较与更新流程 ----------------

    private static void handleRelease(String tag, String notes, String zipUrl) {
        String current = Main.APP_VERSION;
        String latest = stripTagPrefix(tag);
        Log.write("FR-6.9 版本比较：当前=" + current + " 远端=" + latest);

        if (compareVersions(current, latest) >= 0) {
            JOptionPane.showMessageDialog(null,
                    "当前版本：v" + current + "\n最新版本：v" + latest + "\n\n已是最新版本。",
                    "检查更新", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String body = clip(unescapeJson(notes == null ? "" : notes));
        int choice = JOptionPane.showConfirmDialog(null,
                "发现新版本：v" + latest + "（当前 v" + current + "）\n\n"
                        + "—— 更新内容 ——\n" + body + "\n\n"
                        + "选择「是」将下载更新包，完成后应用自动退出并更新重启。",
                "发现新版本", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        File zip = new File(System.getProperty("java.io.tmpdir"),
                "tackshot-update-" + latest + ".zip");
        if (!download(zipUrl, latest, zip)) return;
        install(zip);
    }

    /** 解压更新包到临时 payload 目录（Java 侧完成——bat 调 PowerShell 解压会被部分杀软拦截；
     *  兼容 Compress-Archive 反斜杠条目名，逐条目校验目标路径防 zip-slip）。 */
    private static File extractPayload(File zip) {
        File dest = new File(System.getProperty("java.io.tmpdir"), "tackshot-update-payload");
        deleteTree(dest);
        if (!dest.mkdirs()) return null;
        try (java.util.zip.ZipInputStream zin =
                     new java.util.zip.ZipInputStream(new FileInputStream(zip))) {
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zin.getNextEntry()) != null) {
                File out = new File(dest, e.getName().replace('\\', '/'));
                if (!out.toPath().normalize().startsWith(dest.toPath().normalize()))
                    continue;   // 防 zip-slip：拒绝越出 dest 的条目
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) fo.write(buf, 0, n);
                }
            }
            return dest;
        } catch (IOException e) {
            Log.write("FR-6.9 解压更新包失败：" + e);
            JOptionPane.showMessageDialog(null,
                    "解压更新包失败。\n\n原因：" + e.getMessage()
                            + "\n\n请手动解压覆盖安装目录后重启。",
                    "更新失败", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private static void deleteTree(File f) {
        File[] ch = f.listFiles();
        if (ch != null) for (File c : ch) deleteTree(c);
        f.delete();
    }

    /** 解压 → 写更新脚本 → 启动并退出：脚本等单实例锁释放 → 覆盖 → 重启 start.bat。 */
    private static void install(File zip) {
        File payload = extractPayload(zip);
        if (payload == null) {
            zip.delete();
            return;
        }
        zip.delete();   // 已解压，zip 本体不再需要
        File bat = writeUpdateBat();
        if (bat == null) return;
        try {
            Log.write("FR-6.9 启动更新脚本：" + bat.getAbsolutePath()
                    + "（payload=" + payload.getAbsolutePath() + "）");
            new ProcessBuilder("cmd.exe", "/c", "start", "\"TackShot Update\"", "/min",
                    bat.getAbsolutePath(), payload.getAbsolutePath(), Main.exeDir)
                    .start();
            Main.quit();   // 脚本探测 tackshot.lock 释放（本进程退出）后覆盖并重启
        } catch (Exception e) {
            Log.write("FR-6.9 启动更新脚本失败：" + e);
            JOptionPane.showMessageDialog(null,
                    "更新包已就绪但无法启动更新脚本。\n\n原因：" + e.getMessage()
                            + "\n\n已解压目录：" + payload.getAbsolutePath()
                            + "\n\n请手动复制其中内容覆盖安装目录后重启。",
                    "更新失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------------- 下载（模态进度窗） ----------------

    private static boolean download(String urlStr, String version, File dest) {
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        bar.setString("正在连接…");
        bar.setPreferredSize(new Dimension(380, 22));
        bar.setFont(new Font(Tb.UI_FAMILY, Font.PLAIN, 12));

        JLabel label = new JLabel("正在下载 v" + version + " 更新包，请勿关闭程序…");
        label.setFont(new Font(Tb.UI_FAMILY, Font.PLAIN, 13));

        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        p.add(label, BorderLayout.CENTER);
        p.add(bar, BorderLayout.SOUTH);

        JDialog dlg = dialog(null, "正在更新", true);
        dlg.setContentPane(p);
        dlg.pack();
        dlg.setLocationRelativeTo(null);

        final String[] err = {null};
        Thread worker = new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestProperty("User-Agent", "TackShot-Updater");
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setInstanceFollowRedirects(true);
                int total = conn.getContentLength();
                try (java.io.InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        done += n;
                        final int pct = total > 0 ? (int) (done * 100 / total) : -1;
                        final long d = done, t = total;
                        SwingUtilities.invokeLater(() -> {
                            if (pct >= 0) {
                                bar.setValue(pct);
                                bar.setString(mb(d) + " / " + mb(t) + " MB（" + pct + "%）");
                            } else {
                                bar.setString(mb(d) + " MB");
                            }
                        });
                    }
                }
            } catch (IOException e) {
                err[0] = e.getMessage();
            } finally {
                SwingUtilities.invokeLater(dlg::dispose);
            }
        }, "tackshot-update-download");
        worker.start();

        dlg.setVisible(true);   // 模态等待下载线程结束

        if (err[0] != null) {
            dest.delete();
            Log.write("FR-6.9 下载失败：" + err[0]);
            offerManualDownload("下载失败",
                    "下载更新包时出错。\n\n原因：" + err[0] + "\n\n可选择打开发布页手动下载。");
            return false;
        }
        Log.write("FR-6.9 下载完成：" + dest.getAbsolutePath() + "（" + mb(dest.length()) + " MB）");
        return true;
    }

    // ---------------- 更新脚本 ----------------

    /** 纯 ASCII 脚本，全程零 PowerShell（杀软对 bat 调 PowerShell 高度敏感）：
     *  等待用单实例锁探测——应用运行中 tackshot.lock 被 FileLock 全文件锁定，
     *  copy /b touch 被拒；进程退出锁自动释放，touch 成功。纯 cmd 内建命令。
     *  外部命令（ping/xcopy）显式 System32 路径，防 PATH 污染（Git 自带 timeout.exe 等）。
     *  Args: %1=payload dir（应用已解压好） %2=install dir */
    private static File writeUpdateBat() {
        try {
            File bat = new File(System.getProperty("java.io.tmpdir"), "tackshot-update.bat");
            String script =
                    "@echo off\r\n"
                  + "setlocal EnableExtensions\r\n"
                  + "rem TackShot auto-update. Args: %1=payload dir (already extracted)  %2=install dir\r\n"
                  + "set \"PAYLOAD=%~1\"\r\n"
                  + "set \"DIR=%~2\"\r\n"
                  + "set \"SYS=%SystemRoot%\\System32\"\r\n"
                  + "set \"LOG=%DIR%\\tackshot-update.log\"\r\n"
                  + "\r\n"
                  + "> \"%LOG%\" echo [%DATE% %TIME%] TackShot update start: payload=%PAYLOAD% dir=%DIR%\r\n"
                  + "\r\n"
                  + "rem 1) Wait up to 15 rounds for the app to release its single-instance lock\r\n"
                  + "rem    (tackshot.lock is held by a whole-file lock while the app runs;\r\n"
                  + "rem     the copy touch below fails while locked and succeeds once released)\r\n"
                  + "set /a TRIES=0\r\n"
                  + "if not exist \"%DIR%\\tackshot.lock\" goto exited\r\n"
                  + ":waitloop\r\n"
                  + "copy /b \"%DIR%\\tackshot.lock\"+,, >nul 2>&1\r\n"
                  + "if not errorlevel 1 goto exited\r\n"
                  + "set /a TRIES+=1\r\n"
                  + "if %TRIES% GEQ 15 goto fail\r\n"
                  + "\"%SYS%\\ping.exe\" -n 2 127.0.0.1 >nul\r\n"
                  + "goto waitloop\r\n"
                  + ":exited\r\n"
                  + ">> \"%LOG%\" echo instance lock released after %TRIES% round(s)\r\n"
                  + "\r\n"
                  + "rem 2) Overlay payload over install dir (config.json / logs are not in payload, kept)\r\n"
                  + "set \"SRC=%PAYLOAD%\"\r\n"
                  + "for /d %%D in (\"%PAYLOAD%\\TackShot*\") do set \"SRC=%%D\"\r\n"
                  + ">> \"%LOG%\" echo overlay \"%SRC%\" over \"%DIR%\"\r\n"
                  + "\"%SYS%\\xcopy.exe\" /E /Y /Q \"%SRC%\\*\" \"%DIR%\\\" >>\"%LOG%\" 2>&1\r\n"
                  + "if errorlevel 1 goto fail\r\n"
                  + "\r\n"
                  + "rem 3) Cleanup and restart the app\r\n"
                  + "rmdir /s /q \"%PAYLOAD%\" >nul 2>&1\r\n"
                  + ">> \"%LOG%\" echo update ok, restarting app\r\n"
                  + "cd /d \"%DIR%\"\r\n"
                  + "start \"\" start.bat\r\n"
                  + "\"%SYS%\\ping.exe\" -n 4 127.0.0.1 >nul\r\n"
                  + "del \"%~f0\" >nul 2>&1\r\n"
                  + "exit /b 0\r\n"
                  + "\r\n"
                  + ":fail\r\n"
                  + ">> \"%LOG%\" echo UPDATE FAILED after %TRIES% round(s), app did not exit in time\r\n"
                  + "echo [TackShot] update failed: app still running after timeout. See log: \"%LOG%\"\r\n"
                  + "\"%SYS%\\ping.exe\" -n 16 127.0.0.1 >nul\r\n"
                  + "exit /b 1\r\n";
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(bat), StandardCharsets.US_ASCII)) {
                w.write(script);
            }
            return bat;
        } catch (IOException e) {
            Log.write("FR-6.9 写入 update.bat 失败：" + e);
            JOptionPane.showMessageDialog(null,
                    "无法创建更新脚本。\n\n原因：" + e.getMessage(),
                    "更新失败", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    // ---------------- 工具 ----------------

    /** GET 并返回响应体；非 200 抛出带语义的 IOException（404=无 Release，403=限流）。 */
    private static String httpGet(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "TackShot-Updater");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        int code = conn.getResponseCode();
        if (code == 404) throw new IOException("仓库还没有发布任何 Release（HTTP 404）");
        if (code == 403) throw new IOException("GitHub API 访问受限（HTTP 403，可能是限流，请稍后再试）");
        if (code != 200) throw new IOException("HTTP " + code + " " + conn.getResponseMessage());
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(4096);
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    private static String regexGroup(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** 去掉 tag 前的 v/V 前缀（发布 tag 形如 V2.3.0）。 */
    static String stripTagPrefix(String tag) {
        return tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
    }

    /** 语义化版本比较（纯数字段，以 . 或 - 分隔）：负=a<b，0=相等，正=a>b。 */
    static int compareVersions(String a, String b) {
        int[] pa = parseParts(a), pb = parseParts(b);
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int x = i < pa.length ? pa[i] : 0;
            int y = i < pb.length ? pb[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int[] parseParts(String v) {
        String[] seg = v.split("[.-]");
        int[] r = new int[seg.length];
        for (int i = 0; i < seg.length; i++) {
            String d = seg[i].replaceAll("\\D", "");
            if (d.length() > 9) d = d.substring(0, 9);   // 防超长数字溢出
            r[i] = d.isEmpty() ? 0 : Integer.parseInt(d);
        }
        return r;
    }

    /** JSON 字符串解码：Unicode 四位十六进制转义（Release Notes 中文必需）+ 常见转义。 */
    static String unescapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            if (n == 'u' && i + 4 < s.length()) {
                try {
                    sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                    continue;
                } catch (NumberFormatException ignored) {
                    // 落到 default 按原样输出
                }
            }
            switch (n) {
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                default: sb.append(n);
            }
        }
        return sb.toString();
    }

    /** Release Notes 超长截断。 */
    private static String clip(String notes) {
        String t = notes.replaceAll("\r", "").trim();
        if (t.isEmpty()) return "（本次发布未附更新说明）";
        return t.length() <= NOTES_LIMIT ? t
                : t.substring(0, NOTES_LIMIT) + "\n…（完整说明见发布页）";
    }

    private static long mb(long bytes) {
        return bytes / 1024 / 1024;
    }

    /** 失败时给「打开发布页手动下载」出口：是=浏览器打开 Release 页，否=关闭。 */
    private static void offerManualDownload(String title, String message) {
        int open = JOptionPane.showConfirmDialog(null, message, title,
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (open == JOptionPane.YES_OPTION) {
            try {
                Desktop.getDesktop().browse(java.net.URI.create(RELEASE_PAGE));
            } catch (Exception ignored) {
            }
        }
    }

    private static JDialog dialog(Frame owner, String title, boolean modal) {
        JDialog dlg = new JDialog(owner, title, modal);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(modal ? JDialog.DO_NOTHING_ON_CLOSE : JDialog.DISPOSE_ON_CLOSE);
        return dlg;
    }
}
