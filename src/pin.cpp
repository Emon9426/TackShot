// pin.cpp — 贴图窗口：置顶分层窗口 / 滚轮+边缘拖拽缩放 / 透明度 / 悬浮菜单 / 就地编辑
// V1.10：zoomX/zoomY 分离（四角等比、四边单轴）；顶部预留工具条悬浮区（不遮挡图片，
//        空白条区点击穿透 HTTRANSPARENT）；图片边缘 8 向拖拽调整尺寸。
#include "editor.h"

namespace {

const UINT WM_PRUNE = WM_APP + 3;
enum { TID_HOVER = 1, TID_HIDE = 2, TID_TIP = 3, TID_ANIM = 4, TID_HUD = 5 };
const wchar_t* kPinCls = L"TackShotPin";
const int BORDER = 2;      // 贴图黑色边框宽度
const int STRIP_GAP = 6;   // 工具条与图片间距（逻辑 px）

// ---- 透明度（FR-4.13）：t = 透明度百分比，0%=不透明 ----
const int T_MAX = 95;      // 上限 95%：永不 100% 全透明，保留可见轮廓
int TransPct(BYTE a) { return (int)(((255 - a) * 100 + 127) / 255); }
BYTE AlphaOfT(int t) {
    t = std::max(0, std::min(T_MAX, t));
    return (BYTE)(255 - t * 255 / 100);
}
void CloseAlphaEdit();     // 前向声明（定义在下方输入弹窗段）

// 透明度二级菜单：[ − ][ 75% ][ + ] 三格横排，锚在 ◐ 按钮正下方
struct AlphaFlyout {
    bool visible = false; RECT rc{}; float scale = 1.0f;
    void Layout(const RECT& btn, const RECT& clip, float sc) {
        scale = std::max(1.0f, sc);
        int cw = (int)(34 * scale + 0.5f), ch = (int)(26 * scale + 0.5f);
        int x = (btn.left + btn.right) / 2 - cw;    // 值格中心对准按钮中心
        x = std::max(2, std::min(x, (int)clip.right - cw * 3 - 2));
        rc = { x, btn.bottom + (int)(5 * scale + 0.5f), x + cw * 3,
               btn.bottom + (int)(5 * scale + 0.5f) + ch };
    }
    int Hit(int x, int y) const {   // 1=− 2=值 3=+ 0=未命中
        if (!visible || !PtInRect(&rc, { x, y })) return 0;
        return 1 + std::min(2, std::max(0, (int)((x - rc.left) * 3 / (rc.right - rc.left))));
    }
    void Hide() { visible = false; }
    void Draw(Gdiplus::Graphics& g, int tPct) const {
        using namespace Gdiplus;
        int w = rc.right - rc.left, h = rc.bottom - rc.top, cw = w / 3;
        SolidBrush bg(Color(243, 30, 37, 48));      // #1E2530
        g.FillRectangle(&bg, rc.left, rc.top, w, h);
        SolidBrush mid(Color(70, 51, 65, 85));      // 值格底色稍亮
        g.FillRectangle(&mid, rc.left + cw, rc.top, cw, h);
        Pen bp(Color(255, 51, 65, 85), 1.f);
        g.DrawRectangle(&bp, rc.left, rc.top, w - 1, h - 1);
        FontFamily ff(L"Segoe UI");
        Font f(&ff, 11.f * scale, FontStyleBold, UnitPixel);
        SolidBrush tbc(Color(255, 241, 245, 249));
        StringFormat sf;
        sf.SetAlignment(StringAlignmentCenter);
        sf.SetLineAlignment(StringAlignmentCenter);
        wchar_t t[8];
        swprintf_s(t, 8, L"%d%%", tPct);
        g.DrawString(L"−", -1, &f, RectF((REAL)rc.left, (REAL)rc.top, (REAL)cw, (REAL)h), &sf, &tbc);
        g.DrawString(t, -1, &f, RectF((REAL)(rc.left + cw), (REAL)rc.top, (REAL)cw, (REAL)h), &sf, &tbc);
        g.DrawString(L"+", -1, &f, RectF((REAL)(rc.left + 2 * cw), (REAL)rc.top, (REAL)(w - 2 * cw), (REAL)h), &sf, &tbc);
    }
};

struct PinWindow {
    HWND  wnd = nullptr;
    HBITMAP img = nullptr; void* imgBits = nullptr;
    int   iw = 0, ih = 0;
    Gdiplus::Bitmap* bmp = nullptr;

    double zoomX = 1.0, zoomY = 1.0;    // 允许非等比（四边单轴拉伸）
    BYTE  alpha = 255;

    HBITMAP win = nullptr; HDC winDc = nullptr; void* winBits = nullptr;
    int   ww = 0, wh = 0;
    int   topZone = 0;                   // 顶部工具条悬浮区高度（图片不含在此区内）

    bool  menuVisible = false;
    bool  editing = false;
    bool  shapeDrag = false;
    bool  dead = false;
    bool  tracking = false;

    Toolbar menu;      // 悬浮菜单
    Toolbar bar;       // 就地编辑工具条
    MosaicFlyout flyout;   // 马赛克样式二级菜单（编辑态）
    AlphaFlyout alphaFly;  // 透明度二级菜单（入口菜单 ◐，FR-4.13）
    ULONGLONG sizeHudUntil = 0;
    ULONGLONG alphaHudUntil = 0;
    Editor  ed;
    int     hover = 0;
    ULONGLONG hoverSince = 0;

    // 边缘拖拽调尺寸：-1 无；0..7 同截图控制点（0左上 1上中 2右上 3右中 4右下 5下中 6左下 7左中）
    int   resizing = -1;
    int   rsL = 0, rsT = 0, rsR = 0, rsB = 0;   // 拖拽开始时的窗口四边（屏幕坐标）

    int TopZonePx() { return (int)((24 + STRIP_GAP) * DpiScale(wnd) + 0.5f); }
    int ImgW() { return std::max(8, (int)(iw * zoomX) + 2 * BORDER); }
    int ImgH() { return std::max(8, (int)(ih * zoomY) + 2 * BORDER); }

    POINT ImgPt(int x, int y) {
        return { (int)((x - BORDER) / zoomX), (int)((y - topZone - BORDER) / zoomY) };
    }

    void Render() {
        if (!wnd) return;
        topZone = TopZonePx();
        ww = ImgW();
        wh = ImgH() + topZone;
        if (!win || winSizeW != ww || winSizeH != wh) {
            if (winDc) { DeleteDC(winDc); winDc = nullptr; }
            if (win) { DeleteObject(win); win = nullptr; }
            win = CreateDib32(ww, wh, &winBits);
            winDc = CreateCompatibleDC(NULL);
            SelectObject(winDc, win);
            winSizeW = ww; winSizeH = wh;
        }
        using namespace Gdiplus;
        // 物理清零（关键！）：GDI+ 用全透明刷 FillRectangle 是混合操作、不清屏，
        // 上一帧残影会与当前帧叠加（曾致"编辑条与入口菜单同时可见"缺陷）。
        ZeroMemory(winBits, (SIZE_T)ww * wh * 4);
        Graphics g(winDc);
        g.SetSmoothingMode(SmoothingModeAntiAlias);
        g.SetInterpolationMode(InterpolationModeHighQualityBicubic);
        {
            SolidBrush clear(Color(0, 0, 0, 0));
            g.FillRectangle(&clear, 0, 0, ww, wh);
        }
        int dw = ww - 2 * BORDER, dh = wh - topZone - 2 * BORDER;
        g.DrawImage(bmp, Rect(BORDER, topZone + BORDER, dw, dh), 0, 0, iw, ih, UnitPixel);
        // 黑色边框包住图片区域（编辑态蓝色高亮）
        Pen bd(Color(255, editing ? 59 : 0, editing ? 130 : 0, editing ? 246 : 0), 2.f);
        g.DrawRectangle(&bd, 1.f, topZone + 1.f, ww - 2.f, wh - topZone - 2.f);

        if (editing) {
            g.TranslateTransform((REAL)BORDER, (REAL)(topZone + BORDER));
            g.ScaleTransform((REAL)zoomX, (REAL)zoomY);
            DrawShapes(g, ed.shapes, bmp, POINT{ 0, 0 });
            if (shapeDrag) DrawShape(g, ed.draft, bmp, POINT{ 0, 0 });
            g.ResetTransform();
        }

        // 工具条画在顶部悬浮区（不占图片内容）；空白条区由 WM_NCHITTEST 穿透
        RECT strip{ 0, 0, ww, topZone };
        float sc = DpiScale(wnd);
        float hs = 1.f;
        if (hover && hoverSince) {
            double e = (double)(GetTickCount64() - hoverSince);
            float t = (float)std::min(1.0, e / 160.0);
            hs = 1.f + 0.30f * t * (2.f - t);
        }
        if (editing) {
            bar.Layout(strip, strip, TbMode::PinEdit, sc);
            bar.Draw(winDc, &ed, hover, TbMode::PinEdit, hs);
        } else if (menuVisible) {
            menu.zoomPct = (int)(zoomX * 100 + 0.5);
            menu.Layout(strip, strip, TbMode::PinHover, sc);
            menu.Draw(winDc, nullptr, hover, TbMode::PinHover, hs);
        }
        // 二级菜单展开期间抑制悬停提示条（否则与 ◐ 长提示文字叠在右上角，观感错乱）
        if (hover && hoverSince && !alphaFly.visible &&
            !(editing && flyout.visible) &&
            GetTickCount64() - hoverSince > 300) {
            POINT cp; GetCursorPos(&cp);
            RECT wr; GetWindowRect(wnd, &wr);
            DrawTooltip(g, { cp.x - wr.left, cp.y - wr.top },
                        RECT{ 0, 0, ww, wh }, TbName(hover), sc);
        }

        // 马赛克样式二级菜单与粒度 HUD（FR-3.15，仅编辑态）
        if (editing && flyout.visible) {
            for (auto& b : bar.btns)
                if (b.id == TB_MOSAIC) {
                    flyout.Layout(b.r, RECT{ 0, 0, ww, wh }, sc);
                    break;
                }
            flyout.Draw(winDc, ed, sc);
        }
        if (editing && ed.cur == Tool::Mosaic && sizeHudUntil &&
            GetTickCount64() < sizeHudUntil) {
            POINT cp; GetCursorPos(&cp);
            RECT wr; GetWindowRect(wnd, &wr);
            DrawSizeHud(g, { cp.x - wr.left, cp.y - wr.top }, ed, sc);
        }

        // 透明度二级菜单与 HUD（FR-4.13，入口菜单态）
        if (!editing && menuVisible && alphaFly.visible)
            alphaFly.Draw(g, TransPct(alpha));
        if (alphaHudUntil && GetTickCount64() < alphaHudUntil) {
            POINT cp; GetCursorPos(&cp);
            RECT wr; GetWindowRect(wnd, &wr);
            wchar_t ht[24];
            swprintf_s(ht, 24, L"透明度 %d%%", TransPct(alpha));
            DrawTextHud(g, { cp.x - wr.left, cp.y - wr.top }, ht, sc);
        }

        PremultiplyBits(winBits, ww, wh);

        // 调试转储：环境变量 TACKSHOT_DEBUG_SHOT=1 时落盘当前帧（自动化验证用）
        if (GetEnvironmentVariableW(L"TACKSHOT_DEBUG_SHOT", nullptr, 0)) {
            DWORD hdrlen = sizeof(BITMAPFILEHEADER) + sizeof(BITMAPINFOHEADER);
            DWORD rowsz = (DWORD)ww * 4;
            std::vector<BYTE> buf(hdrlen + rowsz * wh);
            BITMAPFILEHEADER* fh = (BITMAPFILEHEADER*)buf.data();
            BITMAPINFOHEADER* ih = (BITMAPINFOHEADER*)(buf.data() + sizeof(BITMAPFILEHEADER));
            fh->bfType = 0x4D42; fh->bfOffBits = hdrlen; fh->bfSize = (DWORD)buf.size();
            ih->biSize = sizeof(BITMAPINFOHEADER);
            ih->biWidth = ww; ih->biHeight = -wh;
            ih->biPlanes = 1; ih->biBitCount = 32; ih->biCompression = BI_RGB;
            memcpy(buf.data() + hdrlen, winBits, (size_t)rowsz * wh);
            HANDLE df = CreateFileW((g_exeDir + L"\\pinframe.bmp").c_str(),
                                    GENERIC_WRITE, 0, NULL, CREATE_ALWAYS,
                                    FILE_ATTRIBUTE_NORMAL, NULL);
            if (df != INVALID_HANDLE_VALUE) {
                DWORD wr = 0;
                WriteFile(df, buf.data(), (DWORD)buf.size(), &wr, NULL);
                CloseHandle(df);
            }
        }

        HDC sdc = GetDC(NULL);
        POINT src{ 0, 0 }; SIZE sz{ ww, wh };
        // 不透明度走 SourceConstantAlpha（与逐像素 alpha 相乘）。
        // 禁止再用 SetLayeredWindowAttributes——会使 ULW 位图失效导致窗口不可见。
        BLENDFUNCTION bf{ AC_SRC_OVER, 0, alpha, AC_SRC_ALPHA };
        if (!UpdateLayeredWindow(wnd, NULL, NULL, &sz, winDc, &src, 0, &bf, ULW_ALPHA))
            Log(L"警告：UpdateLayeredWindow 失败，GetLastError=%u（贴图可能不可见）", GetLastError());
        ReleaseDC(NULL, sdc);
    }
private:
    int winSizeW = 0, winSizeH = 0;
};

std::vector<std::unique_ptr<PinWindow>> g_pins;

PinWindow* Self(HWND w) { return (PinWindow*)GetWindowLongPtrW(w, GWLP_USERDATA); }

void Prune() {
    g_pins.erase(std::remove_if(g_pins.begin(), g_pins.end(),
                 [](std::unique_ptr<PinWindow>& p) { return p->dead; }),
                 g_pins.end());
}

struct AlphaEdit { HWND wnd = nullptr; PinWindow* pin = nullptr; } g_alphaEdit;

void Close(PinWindow* p) {
    if (p->dead || !p->wnd) return;
    if (TextEntryActive()) CancelTextEntry();
    if (g_alphaEdit.pin == p) CloseAlphaEdit();   // 分层窗口销毁前先关输入弹窗
    DestroyWindow(p->wnd);
}

// 图片区域（含边框，不含顶部悬浮区）的 8 向缩放命中区
int ResizeZone(PinWindow* p, int x, int y) {
    float sc = DpiScale(p->wnd);
    int hz = (int)std::max(6.0, 6.0 * sc) + BORDER;
    int x0 = 0, x1 = p->ww, y0 = p->topZone, y1 = p->wh;
    bool inY = y >= y0 && y <= y1;
    bool inX = x >= x0 && x <= x1;
    bool L = inY && x >= x0 && x < x0 + hz;
    bool R = inY && x >= x1 - hz && x < x1;
    bool T = inX && y >= y0 && y < y0 + hz;
    bool B = inX && y >= y1 - hz && y < y1;
    // 角优先于边：先判 4 个角，再判 4 条边
    if (L && T) return 0; if (R && T) return 2; if (R && B) return 4; if (L && B) return 6;
    if (T) return 1; if (B) return 5; if (L) return 7; if (R) return 3;
    return -1;
}

void DoResize(PinWindow* p) {
    POINT cp; GetCursorPos(&cp);
    int code = p->resizing;
    bool leftMv  = (code == 0 || code == 6 || code == 7);
    bool rightMv = (code == 2 || code == 3 || code == 4);
    bool topMv   = (code == 0 || code == 1 || code == 2);
    bool botMv   = (code == 4 || code == 5 || code == 6);
    double minZ = 0.1, maxZ = 8.0;
    double rx = p->zoomX, ry = p->zoomY;
    if (rightMv) rx = (cp.x - p->rsL - 2.0 * BORDER) / p->iw;
    if (leftMv)  rx = (p->rsR - cp.x - 2.0 * BORDER) / p->iw;
    if (botMv)   ry = (cp.y - p->rsT - p->topZone - 2.0 * BORDER) / p->ih;
    if (topMv)   ry = (p->rsB - cp.y - p->topZone - 2.0 * BORDER) / p->ih;
    if (leftMv || rightMv) {
        if (topMv || botMv) {           // 四角：等比
            double z = std::min(std::max(std::max(rx, ry), minZ), maxZ);
            p->zoomX = p->zoomY = z;
        } else {
            p->zoomX = std::min(std::max(rx, minZ), maxZ);
        }
    } else if (topMv || botMv) {
        p->zoomY = std::min(std::max(ry, minZ), maxZ);
    }
    p->Render();
    int nw = p->ImgW(), nh = p->ImgH() + p->topZone;
    int nx = leftMv ? p->rsR - nw : p->rsL;
    int ny = topMv ? p->rsB - nh : p->rsT;
    SetWindowPos(p->wnd, HWND_TOPMOST, nx, ny, nw, nh, SWP_NOACTIVATE);
    p->Render();
}

// 等比缩放（滚轮 / 菜单按钮）：保持光标下的图像点不动
void ZoomAt(PinWindow* p, double factor, POINT cursorScr) {
    double nz = std::min(8.0, std::max(0.1, p->zoomX * factor));
    p->zoomX = p->zoomY = nz;
    RECT wr; GetWindowRect(p->wnd, &wr);
    POINT anchor = p->ImgPt(cursorScr.x - wr.left, cursorScr.y - wr.top);
    p->Render();
    int nw = p->ImgW(), nh = p->ImgH() + p->topZone;
    int nx = cursorScr.x - (int)(anchor.x * nz) - BORDER;
    int ny = cursorScr.y - (int)(anchor.y * nz) - BORDER - p->topZone;
    SetWindowPos(p->wnd, HWND_TOPMOST, nx, ny, nw, nh, SWP_NOACTIVATE);
    p->Render();
}

void ShowAlphaHud(PinWindow* p) {
    p->alphaHudUntil = GetTickCount64() + 800;
    if (p->wnd) SetTimer(p->wnd, TID_HUD, 90, NULL);
}

void CycleAlpha(PinWindow* p) {          // FR-4.13：0% → 25% → 75% → 0%
    int t = TransPct(p->alpha);
    int nt = t < 12 ? 25 : (t < 50 ? 75 : 0);
    p->alpha = AlphaOfT(nt);
    ShowAlphaHud(p);
    Log(L"透明度循环：%d%%", nt);
    p->Render();   // 重新走 ULW，把常量 alpha 烘进 BLENDFUNCTION
}

// ---- 透明度百分比输入弹窗（顶层 EDIT：分层窗口不能承载子控件）----
void CloseAlphaEdit() {
    if (g_alphaEdit.wnd) {
        HWND h = g_alphaEdit.wnd;
        g_alphaEdit = AlphaEdit{};
        DestroyWindow(h);
    }
}

void ApplyAlphaEdit() {
    if (!g_alphaEdit.wnd || !g_alphaEdit.pin) { CloseAlphaEdit(); return; }
    PinWindow* p = g_alphaEdit.pin;
    wchar_t buf[8]{};
    GetWindowTextW(g_alphaEdit.wnd, buf, 8);
    int v = _wtoi(buf);
    CloseAlphaEdit();
    if (p->dead || !p->wnd) return;
    int t = std::max(0, std::min(T_MAX, v));
    p->alpha = AlphaOfT(t);
    ShowAlphaHud(p);
    if (v != t) Log(L"透明度输入：%d → 夹紧 %d%%（上限 95%%）", v, t);
    else        Log(L"透明度输入：%d%%", t);
    p->Render();
}

LRESULT CALLBACK AlphaEditProc(HWND h, UINT m, WPARAM w, LPARAM l,
                               UINT_PTR, DWORD_PTR) {
    if (m == WM_NCDESTROY) {
        if (g_alphaEdit.wnd == h) g_alphaEdit = AlphaEdit{};
        return DefSubclassProc(h, m, w, l);
    }
    if (m == WM_KEYDOWN) {
        if (w == VK_RETURN) { ApplyAlphaEdit(); return 0; }
        if (w == VK_ESCAPE) { CloseAlphaEdit(); return 0; }
    }
    if (m == WM_KILLFOCUS) { CloseAlphaEdit(); return 0; }
    return DefSubclassProc(h, m, w, l);
}

void OpenAlphaEdit(PinWindow* p) {       // 弹在二级菜单的值格上
    CloseAlphaEdit();
    RECT wr; GetWindowRect(p->wnd, &wr);
    float sc = p->alphaFly.scale;
    int w = (int)(46 * sc + 0.5f), h = (int)(26 * sc + 0.5f);
    int fw = p->alphaFly.rc.right - p->alphaFly.rc.left, cw = fw / 3;
    int x = wr.left + p->alphaFly.rc.left + cw + (cw - w) / 2;
    int y = wr.top + p->alphaFly.rc.top - (h - (p->alphaFly.rc.bottom - p->alphaFly.rc.top)) / 2;
    wchar_t cur[8];
    swprintf_s(cur, 8, L"%d", TransPct(p->alpha));
    HWND e = CreateWindowExW(WS_EX_TOPMOST | WS_EX_TOOLWINDOW, L"EDIT", cur,
                             WS_POPUP | WS_BORDER | ES_NUMBER | ES_CENTER,
                             x, y, w, h, nullptr, nullptr,
                             GetModuleHandleW(nullptr), nullptr);
    if (!e) return;
    SetWindowSubclass(e, AlphaEditProc, 1, 0);
    SendMessageW(e, EM_SETLIMITTEXT, 2, 0);
    HFONT f = CreateFontW(-(int)(13 * sc + 0.5f), 0, 0, 0, FW_SEMIBOLD,
                          0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY,
                          DEFAULT_PITCH, L"Segoe UI");
    SendMessageW(e, WM_SETFONT, (WPARAM)f, TRUE);
    g_alphaEdit = AlphaEdit{ e, p };
    ShowWindow(e, SW_SHOWNORMAL);
    SetFocus(e);
    SendMessageW(e, EM_SETSEL, 0, -1);
    Log(L"透明度输入框：当前 %d%%", TransPct(p->alpha));
}

void SavePin(PinWindow* p) {
    std::wstring dir = g_cfg.output_dir.empty() ? DefaultSaveDir() : g_cfg.output_dir;
    bool jpeg = g_cfg.format == L"jpeg";
    std::wstring path = BuildSavePath(dir, jpeg ? L"jpg" : L"png");
    if (SaveBitmapToFile(p->img, path, jpeg, g_cfg.jpeg_quality))
        Balloon(L"钉图 TackShot", (L"已保存：" + path).c_str());
    else
        Balloon(L"钉图 TackShot", L"保存失败：无法写入目标文件");
}

// 就地编辑完成：合成新图 + 同步剪贴板（FR-4.11）
void ApplyEdit(PinWindow* p) {
    if (TextEntryActive()) CancelTextEntry();
    void* nb = nullptr;
    HBITMAP out = CreateDib32(p->iw, p->ih, &nb);
    if (out) {
        HDC mdc = CreateCompatibleDC(NULL);
        HGDIOBJ old = SelectObject(mdc, out);
        {
            Gdiplus::Graphics g(mdc);
            g.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);
            g.DrawImage(p->bmp, Gdiplus::Rect(0, 0, p->iw, p->ih), 0, 0, p->iw, p->ih,
                        Gdiplus::UnitPixel);
            DrawShapes(g, p->ed.shapes, p->bmp, POINT{ 0, 0 });
        }
        SelectObject(mdc, old);
        DeleteDC(mdc);
        for (int i = 0; i < p->iw * p->ih; ++i) ((BYTE*)nb)[i * 4 + 3] = 0xFF;
        DeleteObject(p->img);
        delete p->bmp;
        p->img = out; p->imgBits = nb;
        p->bmp = new Gdiplus::Bitmap(out, NULL);
        BitmapToClipboard(p->wnd, p->img);
        Balloon(L"钉图 TackShot", L"贴图已编辑，并同步到剪贴板");
    }
    p->editing = false; p->shapeDrag = false;
    p->Render();
}

void OnMenu(PinWindow* p, int id) {
    switch (id) {
    case TB_EDIT:
        p->editing = true;
        p->menuVisible = false;          // 硬不变量：编辑态下入口菜单必须隐藏
        p->hover = 0;
        if (p->wnd) {
            KillTimer(p->wnd, TID_HOVER);
            KillTimer(p->wnd, TID_HIDE);
        }
        p->ed = Editor{};
        SetFocus(p->wnd);
        Log(L"进入贴图编辑（入口菜单强制隐藏）");
        break;
    case TB_COPYIMG:
        BitmapToClipboard(p->wnd, p->img);
        Balloon(L"钉图 TackShot", L"已复制到剪贴板");
        break;
    case TB_SAVE:   SavePin(p); break;
    case TB_ZOOMIN: {
        RECT wr; GetWindowRect(p->wnd, &wr);
        ZoomAt(p, 1.25, { (wr.left + wr.right) / 2, p->topZone + (wr.bottom - wr.top - p->topZone) / 2 });
        break; }
    case TB_ZOOMOUT: {
        RECT wr; GetWindowRect(p->wnd, &wr);
        ZoomAt(p, 0.8, { (wr.left + wr.right) / 2, p->topZone + (wr.bottom - wr.top - p->topZone) / 2 });
        break; }
    case TB_OPAQUE: CycleAlpha(p); break;
    case TB_CLOSE:  Close(p); return;
    default: break;
    }
    p->Render();
}

void OnEditBar(PinWindow* p, int id) {
    switch (id) {
    case TB_OK:     ApplyEdit(p); return;
    case TB_CANCEL: p->editing = false; p->shapeDrag = false; break;
    case TB_RECT: p->ed.cur = Tool::Rect; break;
    case TB_ELLIPSE: p->ed.cur = Tool::Ellipse; break;
    case TB_LINE: p->ed.cur = Tool::Line; break;
    case TB_ARROW: p->ed.cur = Tool::Arrow; break;
    case TB_PEN: p->ed.cur = Tool::Pen; break;
    case TB_TEXT: p->ed.cur = Tool::Text; break;
    case TB_MOSAIC: p->ed.cur = Tool::Mosaic; break;
    case TB_HIGHLIGHT: p->ed.cur = Tool::Highlight; break;
    case TB_UNDO: p->ed.Undo(); break;
    case TB_REDO: p->ed.Redo(); break;
    case TB_C0: case TB_C1: case TB_C2:
    case TB_C3: case TB_C4: case TB_C5: {
        static const DWORD cols[6] = { 0xFFEF4444,0xFFF59E0B,0xFF22C55E,
                                       0xFF3B82F6,0xFFFFFFFF,0xFF111827 };
        p->ed.color.SetValue(cols[id - TB_C0]);
        break; }
    case TB_W0: p->ed.widthIdx = 0; break;
    case TB_W1: p->ed.widthIdx = 1; break;
    case TB_W2: p->ed.widthIdx = 2; break;
    default: break;
    }
    p->Render();
}

LRESULT CALLBACK PinProc(HWND wnd, UINT msg, WPARAM wp, LPARAM lp) {
    PinWindow* p = Self(wnd);
    switch (msg) {
    case WM_NCHITTEST: {
        // 顶部悬浮区：工具条本身可点击，空白条区点击穿透到底下窗口
        if (!p || p->topZone <= 0) break;
        POINT pt{ GET_X_LPARAM(lp), GET_Y_LPARAM(lp) };
        RECT wr; GetWindowRect(wnd, &wr);
        if (pt.y < wr.top + p->topZone) {
            POINT cl{ pt.x - wr.left, pt.y - wr.top };
            bool onBar = p->editing ? PtInRect(&p->bar.bar, cl)
                       : (p->menuVisible && PtInRect(&p->menu.bar, cl));
            if (!p->editing && p->alphaFly.visible && PtInRect(&p->alphaFly.rc, cl))
                onBar = true;               // 透明度二级菜单可点击
            if (onBar) return HTCLIENT;
            return HTTRANSPARENT;
        }
        break; }
    case WM_LBUTTONDOWN: {
        if (!p) break;
        SetFocus(wnd);
        int x = GET_X_LPARAM(lp), y = GET_Y_LPARAM(lp);
        if (!p->editing && p->menuVisible) {
            if (p->alphaFly.visible) {      // 透明度二级菜单（FR-4.13）
                int h = p->alphaFly.Hit(x, y);
                if (h == 1 || h == 3) {
                    int t = TransPct(p->alpha) + (h == 3 ? 10 : -10);
                    p->alpha = AlphaOfT(t);
                    ShowAlphaHud(p);
                    Log(L"透明度步进：%d%%", TransPct(p->alpha));
                    p->Render();
                    break;
                }
                if (h == 2) { OpenAlphaEdit(p); break; }
                p->alphaFly.Hide();         // 点在菜单外：仅关闭，继续处理本次点击
                p->Render();
            }
            int id = p->menu.Hit(x, y);
            if (id) { OnMenu(p, id); break; }
        }
        if (p->editing) {
            // 马赛克样式二级菜单（FR-3.15）
            if (p->flyout.visible) {
                int ms = p->flyout.Hit(x, y);
                p->flyout.Hide();
                if (ms) {
                    p->ed.mosaicStyle = (ms == TB_MS_BLACK) ? 2 : (ms == TB_MS_BLUR ? 1 : 0);
                    p->Render();
                    break;
                }
                // 点在菜单外：仅关闭，继续处理本次点击
            }
            int id = p->bar.Hit(x, y);
            if (id == TB_MOSAIC) {
                RECT cz = MosaicCaretZone(p->bar);
                if (PtInRect(&cz, { x, y })) {      // ▾ 角标：展开样式菜单
                    for (auto& b : p->bar.btns)
                        if (b.id == TB_MOSAIC) {
                            p->flyout.Layout(b.r, RECT{ 0, 0, p->ww, p->wh },
                                             DpiScale(wnd));
                            break;
                        }
                    p->flyout.visible = true;
                    p->Render();
                    break;
                }
                p->flyout.Hide();
            }
            if (id) { OnEditBar(p, id); break; }
            if (x >= BORDER && y >= p->topZone + BORDER &&
                x < p->ww - BORDER && y < p->wh - BORDER) {
                int rz = ResizeZone(p, x, y);
                if (rz >= 0) {          // 编辑态也允许从边缘调整尺寸
                    RECT wr; GetWindowRect(wnd, &wr);
                    p->rsL = wr.left; p->rsT = wr.top;
                    p->rsR = wr.right; p->rsB = wr.bottom;
                    p->resizing = rz;
                    SetCapture(wnd);
                    break;
                }
                POINT ip = p->ImgPt(x, y);
                if (p->ed.cur == Tool::Text) {
                    Gdiplus::ARGB col = p->ed.color.GetValue();
                    int fs = FontSizeFor(p->ed.widthIdx);
                    POINT sp; GetCursorPos(&sp);
                    StartTextEntry(wnd, sp, fs, col, [p, ip, col, fs](const std::wstring& txt) {
                        if (p->dead || !p->wnd) return;
                        if (!txt.empty()) {
                            Shape sh; sh.tool = Tool::Text;
                            sh.color = col; sh.fontSize = fs;
                            sh.a = ip; sh.text = txt;
                            p->ed.Push(sh);
                        }
                        p->Render();
                    });
                    break;
                }
                if (p->ed.cur != Tool::None) {
                    p->shapeDrag = true;
                    SetCapture(wnd);
                    p->ed.draft = Shape{};
                    p->ed.draft.tool = p->ed.cur;
                    p->ed.draft.color = p->ed.color.GetValue();
                    p->ed.draft.penW = (float)PenWidth(p->ed.widthIdx);
                    p->ed.draft.fontSize = FontSizeFor(p->ed.widthIdx);
                    p->ed.draft.mStyle = p->ed.mosaicStyle;
                    p->ed.draft.mSize = p->ed.mosaicSize;
                    p->ed.draft.a = ip; p->ed.draft.b = ip;
                    if (p->ed.cur == Tool::Pen) p->ed.draft.pts.push_back(ip);
                    p->Render();
                    break;
                }
            }
        }
        int rz = ResizeZone(p, x, y);
        if (rz >= 0) {                  // 边缘/四角：拖拽调整尺寸
            RECT wr; GetWindowRect(wnd, &wr);
            p->rsL = wr.left; p->rsT = wr.top;
            p->rsR = wr.right; p->rsB = wr.bottom;
            p->resizing = rz;
            SetCapture(wnd);
            break;
        }
        // 其余情况：拖动窗口
        ReleaseCapture();
        SendMessageW(wnd, WM_NCLBUTTONDOWN, HTCAPTION, 0);
        break; }
    case WM_MOUSEMOVE: {
        if (!p) break;
        if (p->resizing >= 0) { DoResize(p); break; }
        KillTimer(wnd, TID_HIDE);
        int x = GET_X_LPARAM(lp), y = GET_Y_LPARAM(lp);
        int hov = 0;
        if (p->editing) hov = p->bar.Hit(x, y);
        else if (p->menuVisible) hov = p->menu.Hit(x, y);
        if (hov != p->hover) {
            p->hover = hov; p->hoverSince = GetTickCount64();
            KillTimer(wnd, TID_TIP);
            KillTimer(wnd, TID_ANIM);
            if (hov) {
                SetTimer(wnd, TID_TIP, 320, NULL);   // 静止悬停补重绘以显示提示
                SetTimer(wnd, TID_ANIM, 40, NULL);   // 放大动画补绘
            }
            p->Render();
        }
        if (p->shapeDrag) {
            POINT ip = p->ImgPt(x, y);
            if (p->ed.cur == Tool::Pen) p->ed.draft.pts.push_back(ip);
            else p->ed.draft.b = ip;
            p->Render();
        } else {
            // 光标反馈：边缘 8 向缩放 / 图片区移动 / 编辑绘制十字
            int rz = ResizeZone(p, x, y);
            HCURSOR hc = LoadCursor(NULL, IDC_ARROW);
            if (rz == 0 || rz == 4) hc = LoadCursor(NULL, IDC_SIZENWSE);
            else if (rz == 2 || rz == 6) hc = LoadCursor(NULL, IDC_SIZENESW);
            else if (rz == 1 || rz == 5) hc = LoadCursor(NULL, IDC_SIZENS);
            else if (rz == 3 || rz == 7) hc = LoadCursor(NULL, IDC_SIZEWE);
            else if (p->editing && p->ed.cur != Tool::None) {
                static HCURSOR cc = nullptr;
                if (!cc) cc = CreateCrossCursor();
                if (cc) hc = cc;
            } else if (y > p->topZone) hc = LoadCursor(NULL, IDC_SIZEALL);
            SetCursor(hc);
        }
        if (!p->menuVisible && !p->editing && y > p->topZone)
            SetTimer(wnd, TID_HOVER, 330, NULL);
        if (!p->tracking) {
            TRACKMOUSEEVENT tme{ sizeof(tme), TME_LEAVE, wnd, 0 };
            TrackMouseEvent(&tme);
            p->tracking = true;
        }
        break; }
    case WM_TIMER: {
        if (!p) break;
        if (wp == TID_HOVER) {
            KillTimer(wnd, TID_HOVER);
            POINT cp; GetCursorPos(&cp);
            HWND h = WindowFromPoint(cp);
            if (h == wnd && !p->editing && !p->menuVisible) {
                p->menuVisible = true;
                p->Render();
            }
        } else if (wp == TID_HIDE) {
            KillTimer(wnd, TID_HIDE);
            // 二级菜单/输入弹窗打开期间不自动隐藏（用户可能正移向它或在输入）
            if (!p->editing && !p->alphaFly.visible) {
                p->menuVisible = false;
                p->Render();
            }
        } else if (wp == TID_TIP) {
            KillTimer(wnd, TID_TIP);
            if (p->hover) p->Render();
        } else if (wp == TID_ANIM) {
            p->Render();
            if (!p->hover || !p->hoverSince ||
                GetTickCount64() - p->hoverSince > 260)
                KillTimer(wnd, TID_ANIM);
        } else if (wp == TID_HUD) {
            p->Render();
            if ((!p->sizeHudUntil || GetTickCount64() > p->sizeHudUntil) &&
                (!p->alphaHudUntil || GetTickCount64() > p->alphaHudUntil))
                KillTimer(wnd, TID_HUD);
        }
        break; }
    case WM_MOUSELEAVE: {
        if (!p) break;
        p->tracking = false;
        KillTimer(wnd, TID_HOVER);
        if (p->menuVisible && !p->editing) SetTimer(wnd, TID_HIDE, 500, NULL);
        break; }
    case WM_LBUTTONUP: {
        if (!p) break;
        if (p->resizing >= 0) {
            ReleaseCapture();
            p->resizing = -1;
            break;
        }
        if (p->shapeDrag) {
            ReleaseCapture();
            p->shapeDrag = false;
            Shape& d = p->ed.draft;
            bool ok = d.tool == Tool::Pen ? d.pts.size() > 1
                     : (abs(d.b.x - d.a.x) + abs(d.b.y - d.a.y) > 4);
            if (ok) p->ed.Push(d);
            p->Render();
        }
        break; }
    case WM_LBUTTONDBLCLK: {
        if (!p) break;
        int x = GET_X_LPARAM(lp), y = GET_Y_LPARAM(lp);
        if (!p->editing && ResizeZone(p, x, y) < 0) Close(p);
        return 0; }
    case WM_RBUTTONDOWN:
        if (!p) break;
        // 编辑态右键马赛克按钮 = 样式二级菜单（不退出编辑）
        if (p->editing) {
            int rx = GET_X_LPARAM(lp), ry = GET_Y_LPARAM(lp);
            if (p->bar.Hit(rx, ry) == TB_MOSAIC) {
                for (auto& b : p->bar.btns)
                    if (b.id == TB_MOSAIC) {
                        p->flyout.Layout(b.r, RECT{ 0, 0, p->ww, p->wh }, DpiScale(wnd));
                        break;
                    }
                p->flyout.visible = !p->flyout.visible;
                p->Render();
                return 0;
            }
            p->editing = false; p->shapeDrag = false; p->flyout.Hide(); p->Render();
        } else if (p->flyout.visible) {
            p->flyout.Hide(); p->Render();
        }
        else {
            int rx = GET_X_LPARAM(lp), ry = GET_Y_LPARAM(lp);
            if (p->menuVisible && p->menu.Hit(rx, ry) == TB_OPAQUE) {
                // 右键 ◐：透明度二级菜单（FR-4.13）
                for (auto& b : p->menu.btns)
                    if (b.id == TB_OPAQUE) {
                        p->alphaFly.Layout(b.r, RECT{ 0, 0, p->ww, p->wh },
                                           DpiScale(wnd));
                        break;
                    }
                p->alphaFly.visible = !p->alphaFly.visible;
                if (!p->alphaFly.visible) CloseAlphaEdit();
                Log(p->alphaFly.visible ? L"透明度二级菜单：展开"
                                        : L"透明度二级菜单：收起");
                p->Render();
                return 0;
            }
            if (p->alphaFly.visible) {
                p->alphaFly.Hide(); CloseAlphaEdit(); p->Render(); return 0;
            }
            Close(p);
        }
        return 0;
    case WM_MOUSEWHEEL: {
        if (!p) break;
        int delta = GET_WHEEL_DELTA_WPARAM(wp);
        // 编辑态 + 马赛克工具：滚轮调粒度（FR-3.15，用户确认的语义）
        if (p->editing && p->ed.cur == Tool::Mosaic) {
            p->ed.mosaicSize = std::min(80, std::max(6, p->ed.mosaicSize + (delta > 0 ? 4 : -4)));
            p->sizeHudUntil = GetTickCount64() + 800;
            SetTimer(wnd, TID_HUD, 90, NULL);
            p->Render();
            return 0;
        }
        POINT cp; GetCursorPos(&cp);
        if (GetKeyState(VK_CONTROL) & 0x8000) {
            // FR-4.13：Ctrl+滚轮 ±10%，与二级菜单同语义，上限 95% 永不全透明
            int t = TransPct(p->alpha) + (delta > 0 ? -10 : 10);
            p->alpha = AlphaOfT(t);
            ShowAlphaHud(p);
            Log(L"透明度滚轮：%d%%", TransPct(p->alpha));
            p->Render();
        } else {
            ZoomAt(p, delta > 0 ? 1.1 : (1.0 / 1.1), cp);
        }
        return 0; }
    case WM_KEYDOWN: {
        if (!p) break;
        if (TextEntryActive()) break;
        if (wp == VK_ESCAPE) {
            if (!p->editing && p->alphaFly.visible) {
                p->alphaFly.Hide(); CloseAlphaEdit(); p->Render(); return 0;
            }
            if (p->editing && p->flyout.visible) {
                p->flyout.Hide(); p->Render(); return 0;
            }
            if (p->editing) { p->editing = false; p->shapeDrag = false; p->Render(); }
            else Close(p);
            return 0;
        }
        if (p->editing) {
            if (wp == 'Z' && (GetKeyState(VK_CONTROL) & 0x8000)) {
                if (GetKeyState(VK_SHIFT) & 0x8000) p->ed.Redo(); else p->ed.Undo();
                p->Render(); return 0;
            }
            Tool t;
            if (ToolFromKey((UINT)wp, t)) { p->ed.cur = t; p->Render(); return 0; }
        }
        break; }
    case WM_DESTROY: {
        if (p) {
            if (g_alphaEdit.pin == p) CloseAlphaEdit();
            p->dead = true;
            p->wnd = nullptr;
        }
        SetWindowLongPtrW(wnd, GWLP_USERDATA, 0);
        if (g_mainWnd) PostMessageW(g_mainWnd, WM_PRUNE, 0, 0);
        break; }
    }
    return DefWindowProcW(wnd, msg, wp, lp);
}

} // namespace

void RegisterPinClass(HINSTANCE h) {
    WNDCLASSW wc{};
    wc.lpfnWndProc = PinProc;
    wc.hInstance = h;
    wc.lpszClassName = kPinCls;
    wc.style = CS_DBLCLKS;
    RegisterClassW(&wc);
}

void CreatePin(HBITMAP src) {
    if (!src) return;
    BITMAP bm{};
    if (!GetObjectW(src, sizeof(bm), &bm) || bm.bmWidth < 1 || bm.bmHeight < 1) return;
    int iw = bm.bmWidth, ih = bm.bmHeight;

    auto p = std::make_unique<PinWindow>();
    p->iw = iw; p->ih = ih;
    p->imgBits = nullptr;
    p->img = CreateDib32(iw, ih, &p->imgBits);
    if (!p->img) return;
    BITMAPINFO wi{};
    wi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    wi.bmiHeader.biWidth = iw; wi.bmiHeader.biHeight = -ih;
    wi.bmiHeader.biPlanes = 1; wi.bmiHeader.biBitCount = 32;
    wi.bmiHeader.biCompression = BI_RGB;
    HDC tdc = GetDC(NULL);
    GetDIBits(tdc, src, 0, ih, p->imgBits, &wi, DIB_RGB_COLORS);
    ReleaseDC(NULL, tdc);
    for (int i = 0; i < iw * ih; ++i) ((BYTE*)p->imgBits)[i * 4 + 3] = 0xFF;
    p->bmp = new Gdiplus::Bitmap(p->img, NULL);

    // 初始尺寸：不超过工作区 80%
    HMONITOR mon = MonitorFromWindow(GetForegroundWindow(), MONITOR_DEFAULTTONEAREST);
    MONITORINFO mi{ sizeof(mi) };
    RECT avail{ 0, 0, GetSystemMetrics(SM_CXSCREEN), GetSystemMetrics(SM_CYSCREEN) };
    if (GetMonitorInfoW(mon, &mi)) avail = mi.rcWork;
    double fit = std::min(1.0,
        std::min((avail.right - avail.left) * 0.8 / iw,
                 (avail.bottom - avail.top) * 0.8 / ih));
    p->zoomX = p->zoomY = fit;
    p->topZone = (int)((24 + STRIP_GAP) * DpiScale(NULL) + 0.5f);

    int ww = p->ImgW(), wh = p->ImgH() + p->topZone;
    POINT cp; GetCursorPos(&cp);
    // FR-4.1（V1.6）：图片中心锚定确认时的鼠标位置，并夹紧在工作区内
    int x = cp.x - ww / 2;
    int y = cp.y - (wh - p->topZone) / 2;
    x = std::max<int>(avail.left, std::min<int>(x, avail.right - ww));
    y = std::max<int>(avail.top, std::min<int>(y, avail.bottom - wh));

    p->wnd = CreateWindowExW(WS_EX_LAYERED | WS_EX_TOPMOST | WS_EX_TOOLWINDOW,
                             kPinCls, L"", WS_POPUP,
                             x, y, ww, wh,
                             NULL, NULL, g_inst, NULL);
    if (!p->wnd) { DeleteObject(p->img); delete p->bmp; return; }
    SetWindowLongPtrW(p->wnd, GWLP_USERDATA, (LONG_PTR)p.get());
    p->Render();
    ShowWindow(p->wnd, SW_SHOWNOACTIVATE);
    g_pins.push_back(std::move(p));
    Log(L"贴图创建：image %dx%d，当前贴图 %d 个", iw, ih, (int)g_pins.size());
}

int PinCount() {
    return (int)std::count_if(g_pins.begin(), g_pins.end(),
                              [](std::unique_ptr<PinWindow>& p) { return !p->dead; });
}

void CloseAllPins() {
    for (auto& p : g_pins)
        if (!p->dead && p->wnd) DestroyWindow(p->wnd);
    g_pins.clear();
}

// app.cpp 转发：清理已销毁的贴图
extern "C" void TackShot_PrunePins() { Prune(); }
