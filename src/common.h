// common.h — 钉图 TackShot 公共声明
#pragma once
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <windowsx.h>
#include <commctrl.h>
#include <shellapi.h>
#include <shlobj.h>
#include <commdlg.h>
#include <gdiplus.h>
#include <string>
#include <vector>
#include <memory>
#include <algorithm>
#include <filesystem>
#include <cstdint>
#include <cwchar>
#include <cwctype>
#include <cstdarg>

#ifdef _MSC_VER
#pragma comment(lib, "gdiplus.lib")
#pragma comment(lib, "comctl32.lib")
#pragma comment(lib, "comdlg32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "user32.lib")
#pragma comment(lib, "gdi32.lib")
#pragma comment(lib, "advapi32.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "dwmapi.lib")
#endif

extern HINSTANCE   g_inst;
extern HWND        g_mainWnd;
extern std::wstring g_exeDir;

// ---------------- 配置（config.cpp 实现） ----------------
struct Config {
    std::wstring hotkey_region  = L"Ctrl+Alt+A";
    std::wstring hotkey_full    = L"Ctrl+Alt+F";
    std::wstring hotkey_pin     = L"Ctrl+Alt+P";
    std::wstring confirm_action = L"copy_pin";   // copy_pin | copy | copy_save
    std::wstring format         = L"png";        // png | jpeg
    std::wstring output_dir;                    // 空 = 默认（图片\TackShot）
    int  jpeg_quality = 90;

    void Load();
    void Save();
};
extern Config g_cfg;

struct HotKeyParsed { UINT mods; UINT vk; bool ok; };
HotKeyParsed ParseHotkey(const std::wstring& s);

void         Log(const wchar_t* fmt, ...);
std::wstring Sprintf(const wchar_t* fmt, ...);
std::wstring NowStamp();

// util.cpp
HBITMAP CreateDib32(int w, int h, void** bits);
void    PremultiplyBits(void* bits, long w, long h);
bool    BitmapToClipboard(HWND owner, HBITMAP bmp);
HBITMAP BitmapFromClipboard();
bool    SaveBitmapToFile(HBITMAP bmp, const std::wstring& path,
                         bool jpeg, int jpegQuality);
std::wstring DefaultSaveDir();
std::wstring BuildSavePath(const std::wstring& dir, const wchar_t* ext);
HICON   CreateAppIcon();
HCURSOR CreateCrossCursor();   // 自定义十字光标（截图框选用）
void    SetProcessDpiAwareV2();
float   DpiScale(HWND wnd);            // 96 基准的 UI 缩放系数

// app.cpp 提供
void  Balloon(const wchar_t* title, const wchar_t* text);
void  FinishImage(HBITMAP img);   // 取得位图所有权，按配置执行 复制/贴图/保存

// 模块入口（capture.cpp / pin.cpp）
void  RegisterCaptureClass(HINSTANCE h);
void  StartRegionCapture();
void  StartFullscreenCapture();
void  RegisterPinClass(HINSTANCE h);
void  CreatePin(HBITMAP img);     // 克隆位图并创建贴图窗口
int   PinCount();
void  CloseAllPins();
