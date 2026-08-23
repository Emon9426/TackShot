package tackshot;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** tackshot.log：UTF-16LE 追加写（与 C++ 版及回归脚本保持兼容）。 */
final class Log {
    static String dir = ".";
    private static final Object LOCK = new Object();
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Log() {}

    static void write(String msg) {
        String line = "[" + LocalTime.now().format(HMS) + "] " + msg + "\r\n";
        synchronized (LOCK) {
            try (FileOutputStream fo = new FileOutputStream(dir + "\\tackshot.log", true)) {
                fo.write(line.getBytes(StandardCharsets.UTF_16LE));
            } catch (IOException ignored) {
            }
        }
    }
}
