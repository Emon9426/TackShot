// app.cpp — 入口：托盘 / 全局热键 / 单实例 / 开机自启 / 输出分发 / 冒烟测试
#include "common.h"

HINSTANCE   g_inst = nullptr;
HWND        g_mainWnd = nullptr;
std::wstring g_exeDir;

namespace {

const UINT  WM_APP_TRAY = WM_APP + 1;
const UINT  WM_APP_PRUNE = WM_APP + 3;      // 与 pin.cpp 保持一致
const wchar_t* kMainCls = L"TackShotMainWnd";

NOTIFYICONDATAW g_nid{};
HICON  g_icon = nullptr;
ULONG_PTR g_gdipToken = 0;
bool   g_testMode = false;

extern "C" void TackShot_PrunePins();   // pin.cpp 提供

enum {
    IDM_REGION = 4001, IDM_FULL, IDM_PINPASTE, IDM_AUTORUN,
    IDM_ABOUT, IDM_EXIT, IDM_SETTINGS
};
enum { HK_REGION = 1, HK_FULL, HK_PIN };

// ---------------- 托盘 ----------------
void AddTray() {
    g_nid = {};
    g_nid.cbSize = sizeof(g_nid);
    g_nid.hWnd = g_mainWnd;
    g_nid.uID = 1;
    g_nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
    g_nid.uCallbackMessage = WM_APP_TRAY;
    g_nid.hIcon = g_icon;
    wcsncpy_s(g_nid.szTip, L"钉图 TackShot v0.1 · Ctrl+Alt+A 截图", _TRUNCATE);
    Shell_NotifyIconW(NIM_ADD, &g_nid);
}

}

void Balloon(const wchar_t* title, const wchar_t* text) {
    g_nid.uFlags = NIF_INFO;
    wcsncpy_s(g_nid.szInfoTitle, title, _TRUNCATE);
    wcsncpy_s(g_nid.szInfo, text, _TRUNCATE);
    g_nid.dwInfoFlags = NIIF_INFO;
    Shell_NotifyIconW(NIM_MODIFY, &g_nid);
    g_nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
}

namespace {

// ---------------- 开机自启（HKCU Run，默认关闭） ----------------
bool AutoRunEnabled() {
    HKEY k;
    if (RegOpenKeyExW(HKEY_CURRENT_USER,
                      L"Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                      0, KEY_QUERY_VALUE, &k) != ERROR_SUCCESS) return false;
    DWORD type = 0, cb = 0;
    bool on = RegQueryValueExW(k, L"TackShot", NULL, &type, NULL, &cb) == ERROR_SUCCESS;
    RegCloseKey(k);
    return on;
}
void SetAutoRun(bool on) {
    HKEY k;
    if (RegOpenKeyExW(HKEY_CURRENT_USER,
                      L"Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                      0, KEY_SET_VALUE, &k) != ERROR_SUCCESS) return;
    if (on) {
        wchar_t exe[MAX_PATH];
        GetModuleFileNameW(NULL, exe, MAX_PATH);
        std::wstring q = L"\"" + std::wstring(exe) + L"\"";
        RegSetValueExW(k, L"TackShot", 0, REG_SZ,
                       (const BYTE*)q.c_str(), (DWORD)((q.size() + 1) * sizeof(wchar_t)));
    } else {
        RegDeleteValueW(k, L"TackShot");
    }
    RegCloseKey(k);
}

// ---------------- 热键 ----------------
void RegisterAllHotkeys() {
    struct { int id; const std::wstring* key; const wchar_t* name; } hs[] = {
        { HK_REGION, &g_cfg.hotkey_region, L"区域截图" },
        { HK_FULL,   &g_cfg.hotkey_full,   L"全屏截图" },
        { HK_PIN,    &g_cfg.hotkey_pin,    L"贴图" },
    };
    std::wstring fails;
    for (auto& h : hs) {
        HotKeyParsed p = ParseHotkey(*h.key);
        if (!p.ok || !RegisterHotKey(g_mainWnd, h.id, p.mods | MOD_NOREPEAT, p.vk)) {
            fails += std::wstring(h.name) + L"(" + *h.key + L") ";
            Log(L"热键注册失败：%s = %s", h.name, h.key->c_str());
        } else {
            Log(L"热键已注册：%s = %s", h.name, h.key->c_str());
        }
    }
    if (!fails.empty())
        Balloon(L"钉图 TackShot", (L"以下热键被占用，可在 config.json 中修改：" + fails).c_str());
}

void UnregisterAllHotkeys() {
    UnregisterHotKey(g_mainWnd, HK_REGION);
    UnregisterHotKey(g_mainWnd, HK_FULL);
    UnregisterHotKey(g_mainWnd, HK_PIN);
}

void PinFromClipboard() {
    HBITMAP bmp = BitmapFromClipboard();
    if (bmp) {
        CreatePin(bmp);
        DeleteObject(bmp);
    } else {
        Balloon(L"钉图 TackShot", L"剪贴板中没有图片");
    }
}

// ---------------- 输出分发：复制 / 贴图 / 保存 ----------------
void SaveAuto(HBITMAP img, std::wstring* pathOut) {
    std::wstring dir = g_cfg.output_dir.empty() ? DefaultSaveDir() : g_cfg.output_dir;
    bool jpeg = g_cfg.format == L"jpeg";
    std::wstring path = BuildSavePath(dir, jpeg ? L"jpg" : L"png");
    if (SaveBitmapToFile(img, path, jpeg, g_cfg.jpeg_quality) && pathOut)
        *pathOut = path;
}

}

void FinishImage(HBITMAP img) {
    if (!img) return;
    bool copied = BitmapToClipboard(g_mainWnd, img);
    std::wstring msg;
    if (g_cfg.confirm_action == L"copy_pin") {
        CreatePin(img);
        msg = copied ? L"已复制到剪贴板 · 已贴图" : L"已贴图（复制到剪贴板失败）";
    } else if (g_cfg.confirm_action == L"copy_save") {
        std::wstring path;
        SaveAuto(img, &path);
        msg = path.empty() ? L"已复制 · 自动保存失败"
                           : (L"已复制到剪贴板 · 已保存 " + path);
    } else {
        msg = copied ? L"已复制到剪贴板" : L"复制到剪贴板失败";
    }
    Balloon(L"钉图 TackShot", msg.c_str());
    Log(L"输出完成：%s", msg.c_str());
    DeleteObject(img);
}

namespace {

// ---------------- 主窗口过程 ----------------
LRESULT CALLBACK MainProc(HWND wnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_APP_TRAY: {
        if (lp == WM_LBUTTONDBLCLK) StartRegionCapture();
        else if (lp == WM_RBUTTONUP || lp == WM_CONTEXTMENU) {
            POINT pt; GetCursorPos(&pt);
            HMENU m = CreatePopupMenu();
            AppendMenuW(m, MF_STRING, IDM_REGION, L"区域截图\tCtrl+Alt+A");
            AppendMenuW(m, MF_STRING, IDM_FULL, L"全屏截图\tCtrl+Alt+F");
            AppendMenuW(m, MF_STRING, IDM_PINPASTE, L"贴图（剪贴板）\tCtrl+Alt+P");
            AppendMenuW(m, MF_SEPARATOR, 0, NULL);
            AppendMenuW(m, MF_STRING | MF_GRAYED, IDM_SETTINGS, L"设置…（M3 提供）");
            AppendMenuW(m, MF_STRING | (AutoRunEnabled() ? MF_CHECKED : 0),
                        IDM_AUTORUN, L"开机自启动");
            AppendMenuW(m, MF_SEPARATOR, 0, NULL);
            AppendMenuW(m, MF_STRING, IDM_ABOUT, L"关于 钉图 TackShot");
            AppendMenuW(m, MF_STRING, IDM_EXIT, L"退出");
            SetForegroundWindow(wnd);
            int cmd = TrackPopupMenu(m, TPM_RETURNCMD | TPM_RIGHTBUTTON,
                                     pt.x, pt.y, 0, wnd, NULL);
            DestroyMenu(m);
            switch (cmd) {
            case IDM_REGION:   StartRegionCapture(); break;
            case IDM_FULL:     StartFullscreenCapture(); break;
            case IDM_PINPASTE: PinFromClipboard(); break;
            case IDM_SETTINGS: break;
            case IDM_AUTORUN:  SetAutoRun(!AutoRunEnabled()); break;
            case IDM_ABOUT:
                MessageBoxW(wnd,
                    L"钉图 TackShot v0.1\n轻量级开源截图 · 贴图工具\n\n"
                    L"许可证：MIT（见 LICENSE 与 THIRD-PARTY-NOTICES）\n"
                    L"默认热键：Ctrl+Alt+A 区域 / Ctrl+Alt+F 全屏 / Ctrl+Alt+P 贴图\n\n"
                    L"本软件完全离线运行，不收集任何数据。",
                    L"关于", MB_ICONINFORMATION);
                break;
            case IDM_EXIT:
                PostQuitMessage(0);
                break;
            default: break;
            }
        }
        return 0; }
    case WM_HOTKEY: {
        switch (wp) {
        case HK_REGION:   StartRegionCapture(); break;
        case HK_FULL:     StartFullscreenCapture(); break;
        case HK_PIN:      PinFromClipboard(); break;
        }
        return 0; }
    case WM_APP_PRUNE:
        TackShot_PrunePins();
        return 0;
    case WM_DESTROY:
        Shell_NotifyIconW(NIM_DELETE, &g_nid);
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(wnd, msg, wp, lp);
}

// ---------------- 冒烟测试（/test）：保存/剪贴板/贴图链路 ----------------
int RunSmokeTest() {
    Log(L"==== TEST 开始 ====");
    int pass = 0, total = 0;

    // 1) 生成测试图并保存 PNG
    const int W = 240, H = 140;
    void* bits = nullptr;
    HBITMAP bmp = CreateDib32(W, H, &bits);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
            BYTE* px = (BYTE*)bits + ((SIZE_T)y * W + x) * 4;
            px[0] = (BYTE)(x * 255 / W);
            px[1] = (BYTE)(y * 255 / H);
            px[2] = 128; px[3] = 255;
        }
    std::wstring png = g_exeDir + L"\\test_out.png";
    total++;
    if (SaveBitmapToFile(bmp, png, false, 90) && std::filesystem::exists(png)) {
        Log(L"TEST PASS: PNG 保存 (%s)", png.c_str());
        pass++;
    } else Log(L"TEST FAIL: PNG 保存");

    // 2) 剪贴板往返
    total++;
    if (BitmapToClipboard(NULL, bmp) && IsClipboardFormatAvailable(CF_DIB)) {
        Log(L"TEST PASS: 剪贴板写入");
        pass++;
    } else Log(L"TEST FAIL: 剪贴板写入");

    // 3) 贴图窗口创建 + 可见性（分层窗口真实显示，而非仅对象存在）
    total++;
    CreatePin(bmp);
    int n = PinCount();
    HWND pin = FindWindowW(L"TackShotPin", NULL);
    if (n == 1 && pin && IsWindowVisible(pin)) {
        Log(L"TEST PASS: 贴图创建且窗口可见（%d 个）", n);
        pass++;
    } else Log(L"TEST FAIL: 贴图创建（%d 个）/ 可见性（%s）", n, pin ? (IsWindowVisible(pin) ? L"可见" : L"不可见") : L"窗口未找到");

    Log(L"TEST 结果：%d/%d 通过", pass, total);
    Log(L"==== TEST 结束（1.5 秒后退出） ====");

    // 让贴图短暂显示后自动退出（消息循环在 RunSmokeTest 之后的正常循环里跑）
    SetTimer(g_mainWnd, 99, 1500, NULL);
    DeleteObject(bmp);
    return 0;
}

} // namespace

// ---------------- 入口 ----------------
int APIENTRY wWinMain(HINSTANCE hInst, HINSTANCE, LPWSTR cmd, int) {
    g_inst = hInst;
    wchar_t exe[MAX_PATH];
    GetModuleFileNameW(NULL, exe, MAX_PATH);
    g_exeDir = exe;
    size_t slash = g_exeDir.find_last_of(L"\\/");
    if (slash != std::wstring::npos) g_exeDir.resize(slash);

    // 单实例
    HANDLE one = CreateEventW(NULL, TRUE, FALSE, L"Local\\TackShot_SingleInstance");
    if (GetLastError() == ERROR_ALREADY_EXISTS) {
        MessageBoxW(NULL, L"钉图 TackShot 已在运行（请查看系统托盘）。", L"钉图 TackShot", MB_ICONINFORMATION);
        return 0;
    }

    SetProcessDpiAwareV2();
    Gdiplus::GdiplusStartupInput gi;
    Gdiplus::GdiplusStartup(&g_gdipToken, &gi, NULL);
    InitCommonControls();
    g_cfg.Load();
    g_testMode = (cmd && wcsstr(cmd, L"/test") != NULL);
    Log(L"==== 启动 %s（%s）====", L"钉图 TackShot v0.1", g_testMode ? L"测试模式" : L"正常");

    WNDCLASSW wc{};
    wc.lpfnWndProc = MainProc;
    wc.hInstance = hInst;
    wc.lpszClassName = kMainCls;
    RegisterClassW(&wc);
    RegisterCaptureClass(hInst);
    RegisterPinClass(hInst);

    g_mainWnd = CreateWindowExW(0, kMainCls, L"TackShotMain", WS_OVERLAPPEDWINDOW,
                                0, 0, 0, 0, NULL, NULL, hInst, NULL);
    g_icon = CreateAppIcon();
    SendMessageW(g_mainWnd, WM_SETICON, ICON_SMALL, (LPARAM)g_icon);

    AddTray();
    RegisterAllHotkeys();

    int ret = 0;
    if (g_testMode) {
        RunSmokeTest();
    } else {
        Balloon(L"钉图 TackShot 已就绪", L"Ctrl+Alt+A 开始截图 · 右键托盘图标查看菜单");
        if (cmd && wcsstr(cmd, L"/capture"))   // 自动化/测试入口：启动即进入区域截图
            SetTimer(g_mainWnd, 98, 300, NULL);
    }

    MSG msg;
    while (GetMessageW(&msg, NULL, 0, 0) > 0) {
        if (msg.message == WM_TIMER && msg.hwnd == g_mainWnd && msg.wParam == 99) {
            KillTimer(g_mainWnd, 99);
            CloseAllPins();
            PostQuitMessage(0);
            continue;
        }
        if (msg.message == WM_TIMER && msg.hwnd == g_mainWnd && msg.wParam == 98) {
            KillTimer(g_mainWnd, 98);
            StartRegionCapture();
            continue;
        }
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }

    UnregisterAllHotkeys();
    CloseAllPins();
    g_cfg.Save();
    Gdiplus::GdiplusShutdown(g_gdipToken);
    if (one) CloseHandle(one);
    Log(L"==== 退出 ====");
    return ret;
}
