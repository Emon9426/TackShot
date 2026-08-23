// config.cpp — 便携配置（exe 同目录 config.json，扁平键值）与热键解析
#include "common.h"

Config g_cfg;

// ---------------- 极简扁平 JSON 读写（值仅支持字符串/整数） ----------------
static std::wstring Unescape(const std::wstring& s) {
    std::wstring o; o.reserve(s.size());
    for (size_t i = 0; i < s.size(); ++i) {
        if (s[i] == L'\\' && i + 1 < s.size()) {
            wchar_t c = s[++i];
            if (c == L'\\') o += L'\\';
            else if (c == L'"') o += L'"';
            else if (c == L'/') o += L'/';
            else o += c;
        } else o += s[i];
    }
    return o;
}
static std::wstring Escape(const std::wstring& s) {
    std::wstring o; o.reserve(s.size() + 8);
    for (wchar_t c : s) {
        if (c == L'\\') o += L"\\\\";
        else if (c == L'"') o += L"\\\"";
        else o += c;
    }
    return o;
}

// 从 JSON 文本提取 "key" : "value" / "key" : number（有界、只读已知键）
static bool FindValue(const std::wstring& text, const wchar_t* key, std::wstring* out) {
    std::wstring pat = L"\"";
    pat += key; pat += L"\"";
    size_t k = text.find(pat);
    if (k == std::wstring::npos) return false;
    size_t colon = text.find(L':', k + pat.size());
    if (colon == std::wstring::npos) return false;
    size_t vs = text.find_first_not_of(L" \t\r\n", colon + 1);
    if (vs == std::wstring::npos) return false;
    if (text[vs] == L'"') {
        size_t ve = vs + 1;
        while (ve + 1 < text.size() && !(text[ve] == L'"' && text[ve - 1] != L'\\')) ve++;
        if (ve >= text.size()) return false;
        *out = Unescape(text.substr(vs + 1, ve - vs - 1));
        return true;
    }
    size_t ve = text.find_first_of(L",}\r\n", vs);
    if (ve == std::wstring::npos) ve = text.size();
    if (ve > vs + 32) ve = vs + 32;   // 数值限长，防异常输入
    *out = text.substr(vs, ve - vs);
    return true;
}

void Config::Load() {
    std::wstring path = g_exeDir + L"\\config.json";
    HANDLE h = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, NULL,
                           OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (h == INVALID_HANDLE_VALUE) return;
    char raw[8192]; DWORD rd = 0;
    std::string utf8;
    while (ReadFile(h, raw, sizeof(raw), &rd, NULL) && rd > 0) {
        utf8.append(raw, rd);
        if (utf8.size() > 64 * 1024) break;   // 有界读取
    }
    CloseHandle(h);
    if (utf8.empty()) return;
    if (utf8.size() >= 3 && (BYTE)utf8[0] == 0xEF) utf8 = utf8.substr(3); // BOM
    int n = MultiByteToWideChar(CP_UTF8, 0, utf8.data(), (int)utf8.size(), NULL, 0);
    std::wstring text;
    text.resize(n);
    MultiByteToWideChar(CP_UTF8, 0, utf8.data(), (int)utf8.size(), text.data(), n);

    std::wstring v;
    if (FindValue(text, L"hotkey_region", &v) && !v.empty()) hotkey_region = v;
    if (FindValue(text, L"hotkey_full", &v) && !v.empty()) hotkey_full = v;
    if (FindValue(text, L"hotkey_pin", &v) && !v.empty()) hotkey_pin = v;
    if (FindValue(text, L"confirm_action", &v) && !v.empty()) confirm_action = v;
    if (FindValue(text, L"format", &v) && !v.empty()) format = v;
    if (FindValue(text, L"output_dir", &v)) output_dir = v;
    if (FindValue(text, L"jpeg_quality", &v)) {
        int q = _wtoi(v.c_str());
        if (q >= 50 && q <= 100) jpeg_quality = q;
    }
}

void Config::Save() {
    std::wstring path = g_exeDir + L"\\config.json";
    std::wstring json = Sprintf(
        L"{\n"
        L"  \"hotkey_region\": \"%s\",\n"
        L"  \"hotkey_full\": \"%s\",\n"
        L"  \"hotkey_pin\": \"%s\",\n"
        L"  \"confirm_action\": \"%s\",\n"
        L"  \"format\": \"%s\",\n"
        L"  \"output_dir\": \"%s\",\n"
        L"  \"jpeg_quality\": %d\n"
        L"}\n",
        Escape(hotkey_region).c_str(), Escape(hotkey_full).c_str(),
        Escape(hotkey_pin).c_str(), Escape(confirm_action).c_str(),
        Escape(format).c_str(), Escape(output_dir).c_str(), jpeg_quality);
    HANDLE h = CreateFileW(path.c_str(), GENERIC_WRITE, 0, NULL,
                           CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (h == INVALID_HANDLE_VALUE) return;
    int n = WideCharToMultiByte(CP_UTF8, 0, json.c_str(), (int)json.size(), NULL, 0, NULL, NULL);
    std::string utf8;
    utf8.resize(n);
    WideCharToMultiByte(CP_UTF8, 0, json.c_str(), (int)json.size(), utf8.data(), n, NULL, NULL);
    DWORD wr = 0;
    WriteFile(h, utf8.data(), (DWORD)utf8.size(), &wr, NULL);
    CloseHandle(h);
}

// ---------------- 热键字符串解析："Ctrl+Alt+A" ----------------
HotKeyParsed ParseHotkey(const std::wstring& s) {
    HotKeyParsed hk{ 0, 0, false };
    std::wstring t; t.reserve(s.size());
    for (wchar_t c : s) t += (c == L' ') ? L'+' : towupper(c);
    std::vector<std::wstring> parts;
    size_t pos = 0;
    while (pos <= t.size()) {
        size_t np = t.find(L'+', pos);
        if (np == std::wstring::npos) { parts.push_back(t.substr(pos)); break; }
        parts.push_back(t.substr(pos, np - pos));
        pos = np + 1;
    }
    if (parts.empty()) return hk;
    for (auto& p : parts) {
        if (p.empty()) continue;
        if (p == L"CTRL") hk.mods |= MOD_CONTROL;
        else if (p == L"ALT") hk.mods |= MOD_ALT;
        else if (p == L"SHIFT") hk.mods |= MOD_SHIFT;
        else if (p == L"WIN" || p == L"META") hk.mods |= MOD_WIN;
        else if (p.size() == 1 && p[0] >= L'A' && p[0] <= L'Z') hk.vk = p[0];
        else if (p.size() == 1 && p[0] >= L'0' && p[0] <= L'9') hk.vk = p[0];
        else if (p.size() >= 2 && p[0] == L'F') {
            int f = _wtoi(p.c_str() + 1);
            if (f >= 1 && f <= 12) hk.vk = VK_F1 + f - 1;
        } else if (p == L"PRTSC" || p == L"PRINTSCREEN") hk.vk = VK_SNAPSHOT;
        else return hk;
    }
    if (hk.vk) hk.ok = true;
    return hk;
}
