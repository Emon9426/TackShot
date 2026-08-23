// util.cpp — 基础工具：日志 / DIB / 剪贴板 / 文件保存 / 图标
#include <initguid.h>   // 内联实例化 FOLDERID_* 等 GUID
#include "common.h"
#include <knownfolders.h>

// ---------------- 日志（本地滚动，全部有界写入） ----------------
void Log(const wchar_t* fmt, ...) {
    wchar_t buf[1024];
    va_list ap; va_start(ap, fmt);
    _vsnwprintf_s(buf, 1024, _TRUNCATE, fmt, ap);
    va_end(ap);
    SYSTEMTIME st; GetLocalTime(&st);
    wchar_t line[1200];
    _snwprintf_s(line, 1200, _TRUNCATE, L"[%02d:%02d:%02d] %s\r\n",
                 st.wHour, st.wMinute, st.wSecond, buf);
    OutputDebugStringW(line);
    std::wstring path = g_exeDir + L"\\tackshot.log";
    HANDLE h = CreateFileW(path.c_str(), FILE_APPEND_DATA, FILE_SHARE_READ,
                           NULL, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (h != INVALID_HANDLE_VALUE) {
        DWORD wr = 0; WriteFile(h, line, (DWORD)(wcslen(line) * sizeof(wchar_t)), &wr, NULL);
        CloseHandle(h);
    }
}

std::wstring Sprintf(const wchar_t* fmt, ...) {
    wchar_t buf[1024];
    va_list ap; va_start(ap, fmt);
    _vsnwprintf_s(buf, 1024, _TRUNCATE, fmt, ap);
    va_end(ap);
    return buf;
}

std::wstring NowStamp() {
    SYSTEMTIME st; GetLocalTime(&st);
    wchar_t b[32];
    _snwprintf_s(b, 32, _TRUNCATE, L"%04u%02u%02u_%02u%02u%02u",
                 st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond);
    return b;
}

// ---------------- DIB ----------------
HBITMAP CreateDib32(int w, int h, void** bits) {
    BITMAPINFO bi{};
    bi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bi.bmiHeader.biWidth = w;
    bi.bmiHeader.biHeight = -h;             // top-down
    bi.bmiHeader.biPlanes = 1;
    bi.bmiHeader.biBitCount = 32;
    bi.bmiHeader.biCompression = BI_RGB;
    void* p = nullptr;
    HBITMAP hb = CreateDIBSection(NULL, &bi, DIB_RGB_COLORS, &p, NULL, 0);
    if (bits) *bits = p;
    return hb;
}

// GDI+ 画到 DIB 上是"直透 alpha"，ULW 需要"预乘 alpha"
void PremultiplyBits(void* bits, long w, long h) {
    BYTE* px = (BYTE*)bits;
    for (long i = 0; i < w * h; ++i, px += 4) {
        px[0] = (BYTE)((px[0] * px[3] + 127) / 255);
        px[1] = (BYTE)((px[1] * px[3] + 127) / 255);
        px[2] = (BYTE)((px[2] * px[3] + 127) / 255);
    }
}

// ---------------- 剪贴板 ----------------
bool BitmapToClipboard(HWND owner, HBITMAP bmp) {
    if (!bmp) return false;
    BITMAP bm{};
    if (!GetObjectW(bmp, sizeof(bm), &bm) || bm.bmWidth <= 0 || bm.bmHeight <= 0) return false;
    int w = bm.bmWidth, h = bm.bmHeight;
    // 读出为 bottom-up 32bpp DIB（CF_DIB 惯例）
    BITMAPINFO srcInfo{};
    srcInfo.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    srcInfo.bmiHeader.biWidth = w;
    srcInfo.bmiHeader.biHeight = -h;        // 先按 top-down 读
    srcInfo.bmiHeader.biPlanes = 1;
    srcInfo.bmiHeader.biBitCount = 32;
    srcInfo.bmiHeader.biCompression = BI_RGB;
    std::vector<BYTE> top(w * h * 4);
    HDC dc = GetDC(NULL);
    if (!GetDIBits(dc, bmp, 0, h, top.data(), &srcInfo, DIB_RGB_COLORS)) { ReleaseDC(NULL, dc); return false; }
    ReleaseDC(NULL, dc);

    SIZE_T total = sizeof(BITMAPINFOHEADER) + (SIZE_T)w * h * 4;
    HGLOBAL hg = GlobalAlloc(GMEM_MOVEABLE, total);
    if (!hg) return false;
    BYTE* dst = (BYTE*)GlobalLock(hg);
    if (!dst) { GlobalFree(hg); return false; }
    BITMAPINFOHEADER* ih = (BITMAPINFOHEADER*)dst;
    ih->biSize = sizeof(BITMAPINFOHEADER);
    ih->biWidth = w; ih->biHeight = h;      // bottom-up
    ih->biPlanes = 1; ih->biBitCount = 32; ih->biCompression = BI_RGB;
    ih->biSizeImage = (DWORD)(w * h * 4);
    ih->biXPelsPerMeter = 0; ih->biYPelsPerMeter = 0; ih->biClrUsed = 0; ih->biClrImportant = 0;
    BYTE* body = dst + sizeof(BITMAPINFOHEADER);
    for (int y = 0; y < h; ++y) {
        BYTE* srcRow = top.data() + (SIZE_T)(h - 1 - y) * w * 4;
        BYTE* dstRow = body + (SIZE_T)y * w * 4;
        for (int x = 0; x < w; ++x) {
            dstRow[x * 4 + 0] = srcRow[x * 4 + 0];
            dstRow[x * 4 + 1] = srcRow[x * 4 + 1];
            dstRow[x * 4 + 2] = srcRow[x * 4 + 2];
            dstRow[x * 4 + 3] = 0xFF;       // 不透明，兼容各应用粘贴
        }
    }
    GlobalUnlock(hg);
    if (!OpenClipboard(owner)) { GlobalFree(hg); return false; }
    EmptyClipboard();
    HANDLE hres = SetClipboardData(CF_DIB, hg);
    CloseClipboard();
    if (!hres) { GlobalFree(hg); return false; }
    return true;
}

HBITMAP BitmapFromClipboard() {
    if (!IsClipboardFormatAvailable(CF_DIB)) return NULL;
    if (!OpenClipboard(NULL)) return NULL;
    HGLOBAL hg = GetClipboardData(CF_DIB);
    HBITMAP out = NULL;
    if (hg) {
        BYTE* p = (BYTE*)GlobalLock(hg);
        if (p) {
            BITMAPINFOHEADER* ih = (BITMAPINFOHEADER*)p;
            int w = ih->biWidth;
            int bottomUp = 1;
            int h = ih->biHeight;
            if (h < 0) { h = -h; bottomUp = 0; }
            if (w > 0 && h > 0 && w < 32768 && h < 32768 &&
                (ih->biBitCount == 24 || ih->biBitCount == 32) &&
                ih->biCompression == BI_RGB) {
                void* bits = nullptr;
                out = CreateDib32(w, h, &bits);
                if (out) {
                    BYTE* body = p + ih->biSize + (ih->biClrUsed ? ih->biClrUsed * 4 : 0);
                    int srcBpp = ih->biBitCount / 8;
                    for (int y = 0; y < h; ++y) {
                        int sy = bottomUp ? (h - 1 - y) : y;
                        BYTE* srow = body + (SIZE_T)sy * w * srcBpp;
                        BYTE* drow = (BYTE*)bits + (SIZE_T)y * w * 4;
                        for (int x = 0; x < w; ++x) {
                            drow[x*4+0] = srow[x*srcBpp+0];
                            drow[x*4+1] = srow[x*srcBpp+1];
                            drow[x*4+2] = srow[x*srcBpp+2];
                            drow[x*4+3] = 0xFF;
                        }
                    }
                }
            }
            GlobalUnlock(hg);
        }
    }
    CloseClipboard();
    return out;
}

// ---------------- 文件保存 ----------------
static int GetEncoderClsid(const wchar_t* mime, CLSID* clsid) {
    using namespace Gdiplus;
    UINT num = 0, size = 0;
    GetImageEncodersSize(&num, &size);
    if (!size) return -1;
    std::vector<BYTE> buf(size);
    ImageCodecInfo* infos = (ImageCodecInfo*)buf.data();
    GetImageEncoders(num, size, infos);
    for (UINT i = 0; i < num; ++i)
        if (wcscmp(infos[i].MimeType, mime) == 0) { *clsid = infos[i].Clsid; return (int)i; }
    return -1;
}

bool SaveBitmapToFile(HBITMAP bmp, const std::wstring& path, bool jpeg, int jpegQuality) {
    using namespace Gdiplus;
    Bitmap img(bmp, NULL);
    CLSID enc{};
    const wchar_t* mime = jpeg ? L"image/jpeg" : L"image/png";
    if (GetEncoderClsid(mime, &enc) < 0) return false;
    Status st;
    if (jpeg) {
        EncoderParameters ep{};
        ep.Count = 1;
        ep.Parameter[0].Guid = EncoderQuality;
        ep.Parameter[0].Type = EncoderParameterValueTypeLong;
        ep.Parameter[0].NumberOfValues = 1;
        ULONG q = (ULONG)jpegQuality;
        ep.Parameter[0].Value = &q;
        st = img.Save(path.c_str(), &enc, &ep);
    } else {
        st = img.Save(path.c_str(), &enc, NULL);
    }
    return st == Ok;
}

std::wstring DefaultSaveDir() {
    wchar_t* pics = nullptr;
    std::wstring dir;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_Pictures, 0, NULL, &pics))) {
        dir = std::wstring(pics) + L"\\TackShot";
        CreateDirectoryW(dir.c_str(), NULL);
    }
    if (pics) CoTaskMemFree(pics);
    if (dir.empty()) dir = g_exeDir;
    return dir;
}

std::wstring BuildSavePath(const std::wstring& dir, const wchar_t* ext) {
    namespace fs = std::filesystem;
    for (int i = 0; i < 100; ++i) {
        std::wstring name = (i == 0) ? NowStamp() : NowStamp() + L"_" + std::to_wstring(i);
        std::wstring p = dir + L"\\" + name + L"." + ext;
        if (!fs::exists(p)) return p;
    }
    return dir + L"\\" + NowStamp() + L"." + ext;
}

// ---------------- 程序图标（运行时矢量绘制，无资源文件） ----------------
// 自定义十字光标：白芯黑描边，任意背景可见，提示"可自由框选"
HCURSOR CreateCrossCursor() {
    const int S = 32;
    void* bits = nullptr;
    HBITMAP dib = CreateDib32(S, S, &bits);
    if (!dib) return NULL;
    HDC dc = CreateCompatibleDC(NULL);
    HGDIOBJ old = SelectObject(dc, dib);
    {
        using namespace Gdiplus;
        Graphics g(dc);
        SolidBrush clear(Color(0, 0, 0, 0));
        g.FillRectangle(&clear, 0, 0, S, S);
        Pen blk(Color(255, 0, 0, 0), 5.f);
        Pen wht(Color(255, 255, 255, 255), 2.f);
        for (int pass = 0; pass < 2; ++pass) {
            Pen* p = pass == 0 ? &blk : &wht;
            g.DrawLine(p, 16, 0, 16, 11);
            g.DrawLine(p, 16, 21, 16, 31);
            g.DrawLine(p, 0, 16, 11, 16);
            g.DrawLine(p, 21, 16, 31, 16);
        }
    }
    SelectObject(dc, old);
    DeleteDC(dc);
    HBITMAP mask = CreateBitmap(S, S, 1, 1, NULL);
    ICONINFO ii{ FALSE, 16, 16, mask, dib };
    return (HCURSOR)CreateIconIndirect(&ii);
}

HICON CreateAppIcon() {
    const int S = 32;
    void* bits = nullptr;
    HBITMAP dib = CreateDib32(S, S, &bits);
    HDC dc = CreateCompatibleDC(NULL);
    HGDIOBJ old = SelectObject(dc, dib);
    {
        using namespace Gdiplus;
        Graphics g(dc);
        g.SetSmoothingMode(SmoothingModeAntiAlias);
        // 圆角深色底
        GraphicsPath bg;
        bg.AddArc(1, 1, 30, 30, 0, 180);
        bg.AddArc(1, 1, 30, 30, 180, 180);
        bg.CloseFigure();
        SolidBrush b1(Color(255, 30, 37, 54));
        g.FillPath(&b1, &bg);
        // 方头图钉（琥珀色）：方头 + 白描边，避免"圆+柄"的放大镜观感
        Pen wpen(Color(255, 255, 255, 255), 2.0f);
        SolidBrush head(Color(255, 245, 158, 11));
        RectF hr(8.5f, 5.5f, 15.f, 10.f);
        GraphicsPath hp;
        REAL r = 3.f;
        hp.AddArc(hr.GetLeft(), hr.GetTop(), r * 2, r * 2, 180, 90);
        hp.AddArc(hr.GetRight() - r * 2, hr.GetTop(), r * 2, r * 2, 270, 90);
        hp.AddArc(hr.GetRight() - r * 2, hr.GetBottom() - r * 2, r * 2, r * 2, 0, 90);
        hp.AddArc(hr.GetLeft(), hr.GetBottom() - r * 2, r * 2, r * 2, 90, 90);
        hp.CloseFigure();
        g.FillPath(&head, &hp);
        g.DrawPath(&wpen, &hp);
        // 针杆：从钉头中下向右下刺出
        Pen np(Color(255, 226, 232, 240), 3.f);
        np.SetStartCap(LineCapRound); np.SetEndCap(LineCapRound);
        g.DrawLine(&np, 16, 16, 21, 28);
        // 钉头顶部高光
        Pen hl(Color(255, 254, 243, 199), 1.6f);
        g.DrawLine(&hl, 11, 8, 19, 8);
    }
    SelectObject(dc, old);
    DeleteDC(dc);
    HBITMAP mask = CreateBitmap(S, S, 1, 1, NULL);
    ICONINFO ii{ TRUE, 0, 0, mask, dib };
    HICON icon = CreateIconIndirect(&ii);
    return icon;
}

// ---------------- DPI 感知（Per-Monitor V2） ----------------
void SetProcessDpiAwareV2() {    typedef BOOL(WINAPI * SPDAC_t)(HANDLE);
    typedef HRESULT(WINAPI * SPDA_t)(int);
    HMODULE u32 = GetModuleHandleW(L"user32.dll");
    if (u32) {
        SPDAC_t f = (SPDAC_t)GetProcAddress(u32, "SetProcessDpiAwarenessContext");
        if (f && f((HANDLE)-4 /* PER_MONITOR_AWARE_V2 */)) return;
    }
    HMODULE shc = LoadLibraryW(L"shcore.dll");
    if (shc) {
        SPDA_t f = (SPDA_t)GetProcAddress(shc, "SetProcessDpiAwareness");
        if (f && SUCCEEDED(f(2 /*PER_MONITOR_DPI_AWARE*/))) return;
    }
    SetProcessDPIAware();
}

// UI 缩放系数：优先窗口 DPI，其次主屏 DC DPI（96 基准，最小 1.0）
float DpiScale(HWND wnd) {
    typedef UINT(WINAPI* GDW_t)(HWND);
    static GDW_t f = nullptr;
    if (!f) f = (GDW_t)(void*)GetProcAddress(GetModuleHandleW(L"user32.dll"),
                                            "GetDpiForWindow");
    UINT dpi = 0;
    if (f && wnd) dpi = f(wnd);
    if (!dpi) {
        HDC dc = GetDC(NULL);
        dpi = (UINT)GetDeviceCaps(dc, LOGPIXELSX);
        ReleaseDC(NULL, dc);
    }
    if (dpi < 96) dpi = 96;
    return dpi / 96.0f;
}
