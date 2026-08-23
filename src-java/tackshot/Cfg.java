package tackshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/** 便携配置：jar 同目录 config.json，扁平键值，格式与 C++ 版完全兼容。 */
final class Cfg {
    String hotkeyRegion = "Ctrl+Alt+A";
    String hotkeyFull = "Ctrl+Alt+F";
    String hotkeyPin = "Ctrl+Alt+P";
    String confirmAction = "copy_pin";   // copy_pin | copy | copy_save
    String format = "png";               // png | jpeg
    String outputDir = "";               // 空 = 默认（图片\TackShot）
    int jpegQuality = 90;

    void load() {
        String text;
        try {
            byte[] raw = Files.readAllBytes(Paths.get(Main.exeDir, "config.json"));
            String utf8 = new String(raw, StandardCharsets.UTF_8);
            if (!utf8.isEmpty() && utf8.charAt(0) == '\uFEFF') utf8 = utf8.substring(1);
            text = utf8;
        } catch (IOException e) {
            return;
        }
        String v;
        if ((v = findValue(text, "hotkey_region")) != null && !v.isEmpty()) hotkeyRegion = v;
        if ((v = findValue(text, "hotkey_full")) != null && !v.isEmpty()) hotkeyFull = v;
        if ((v = findValue(text, "hotkey_pin")) != null && !v.isEmpty()) hotkeyPin = v;
        if ((v = findValue(text, "confirm_action")) != null && !v.isEmpty()) confirmAction = v;
        if ((v = findValue(text, "format")) != null && !v.isEmpty()) format = v;
        if ((v = findValue(text, "output_dir")) != null) outputDir = v;
        if ((v = findValue(text, "jpeg_quality")) != null) {
            try {
                int q = Integer.parseInt(v.trim());
                if (q >= 50 && q <= 100) jpegQuality = q;
            } catch (NumberFormatException ignored) {
            }
        }
    }

    void save() {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"hotkey_region\": \"").append(escape(hotkeyRegion)).append("\",\n");
        json.append("  \"hotkey_full\": \"").append(escape(hotkeyFull)).append("\",\n");
        json.append("  \"hotkey_pin\": \"").append(escape(hotkeyPin)).append("\",\n");
        json.append("  \"confirm_action\": \"").append(escape(confirmAction)).append("\",\n");
        json.append("  \"format\": \"").append(escape(format)).append("\",\n");
        json.append("  \"output_dir\": \"").append(escape(outputDir)).append("\",\n");
        json.append("  \"jpeg_quality\": ").append(jpegQuality).append("\n");
        json.append("}\n");
        try {
            Files.write(Paths.get(Main.exeDir, "config.json"), json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private static String unescape(String s) {
        StringBuilder o = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) o.append(s.charAt(++i));
            else o.append(c);
        }
        return o.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String findValue(String text, String key) {
        String pat = "\"" + key + "\"";
        int k = text.indexOf(pat);
        if (k < 0) return null;
        int colon = text.indexOf(':', k + pat.length());
        if (colon < 0) return null;
        int vs = colon + 1;
        while (vs < text.length() && " \t\r\n".indexOf(text.charAt(vs)) >= 0) vs++;
        if (vs >= text.length()) return null;
        if (text.charAt(vs) == '"') {
            int ve = vs + 1;
            while (ve + 1 < text.length() && !(text.charAt(ve) == '"' && text.charAt(ve - 1) != '\\')) ve++;
            if (ve >= text.length()) return null;
            return unescape(text.substring(vs + 1, ve));
        }
        int ve = vs;
        while (ve < text.length() && ",}\r\n".indexOf(text.charAt(ve)) < 0) ve++;
        if (ve - vs > 32) ve = vs + 32;
        return text.substring(vs, ve);
    }

    static final class Hot {
        final int mods, vk;
        final boolean ok;
        Hot(int mods, int vk, boolean ok) { this.mods = mods; this.vk = vk; this.ok = ok; }
    }

    /** 解析 "Ctrl+Alt+A" 形式热键串；语义与 C++ ParseHotkey 一致。 */
    static Hot parseHotkey(String s) {
        StringBuilder t = new StringBuilder(s.length());
        for (char c : s.toCharArray())
            t.append(c == ' ' ? '+' : Character.toUpperCase(c));
        String[] parts = t.toString().split("\\+", -1);
        int mods = 0, vk = 0;
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (p.equals("CTRL")) mods |= 0x2;
            else if (p.equals("ALT")) mods |= 0x1;
            else if (p.equals("SHIFT")) mods |= 0x4;
            else if (p.equals("WIN") || p.equals("META")) mods |= 0x8;
            else if (p.length() == 1 && ((p.charAt(0) >= 'A' && p.charAt(0) <= 'Z')
                    || (p.charAt(0) >= '0' && p.charAt(0) <= '9'))) vk = p.charAt(0);
            else if (p.length() >= 2 && p.charAt(0) == 'F') {
                try {
                    int f = Integer.parseInt(p.substring(1));
                    if (f >= 1 && f <= 12) vk = 0x70 + f - 1;
                } catch (NumberFormatException ignored) {
                }
            } else if (p.equals("PRTSC") || p.equals("PRINTSCREEN")) vk = 0x2C;
            else return new Hot(0, 0, false);
        }
        return new Hot(mods, vk, vk != 0);
    }
}
