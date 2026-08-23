// pin.cpp — 贴图窗口：置顶分层窗口 / 缩放 / 透明度 / 悬浮菜单 / 就地编辑
#include "editor.h"

namespace {

const UINT WM_PRUNE = WM_APP + 3;
enum { TID_HOVER = 1, TID_HIDE = 2 };
const wchar_t* kPinCls = L"TackShotPin";

struct PinWindow {
    HWND  wnd = nullptr;
    HBITMAP img = nullptr; void* imgBits = nullptr;
    int   iw = 0, ih = 0;
    Gdiplus::Bitmap* bmp = nullptr;

    double zoom = 1.0;
    BYTE  alpha = 255;

    HBITMAP win = nullptr; HDC winDc = nullptr; void* winBits = nullptr;
    int   ww = 0, wh = 0;

    bool  menuVisible = false;
    bool  editing = false;
    bool  shapeDrag = false;
    bool  dead = false;
    bool  tracking = false;

    Toolbar menu;      // 悬浮菜单
    Toolbar bar;      // 就地编辑工具条
    Editor  ed;
    int     hover = 0;

    POINT ImgPt(int x, int y) {
        return { (int)((x - 1) / zoom), (int)((y - 1) / zoom) };
    }

    void Render() {
        if (!wnd) return;
        ww = std::max(8, (int)(iw * zoom) + 2);
        wh = std::max(8, (int)(ih * zoom) + 2);
        if (!win || winSizeW != ww || winSizeH != wh) {
            if (winDc) { DeleteDC(winDc); winDc = nullptr; }
            if (win) { DeleteObject(win); win = nullptr; }
            win = CreateDib32(ww, wh, &winBits);
            winDc = CreateCompatibleDC(NULL);
            SelectObject(winDc, win);
            winSizeW = ww; winSizeH = wh;
        }
        using namespace Gdiplus;
        Graphics g(winDc);
        g.SetSmoothingMode(SmoothingModeAntiAlias);
        g.SetInterpolationMode(InterpolationModeHighQualityBicubic);
        // 全透明清底
        RECT fr{ 0, 0, ww, wh };
        FillRect(winDc, &fr, (HBRUSH)GetStockObject(BLACK_BRUSH));
        {
            SolidBrush clear(Color(0, 0, 0, 0));
            g.FillRectangle(&clear, 0, 0, ww, wh);
        }
        int dw = ww - 2, dh = wh - 2;
        g.DrawImage(bmp, Rect(1, 1, dw, dh), 0, 0, iw, ih, UnitPixel);
        Pen bd(Color(editing ? 255 : 190, editing ? 59 : 148, 130, (editing ? 246 : 184)), 1.f);
        g.DrawRectangle(&bd, 0.5f, 0.5f, ww - 1.f, wh - 1.f);

        if (editing) {
            g.TranslateTransform(1.f, 1.f);
            g.ScaleTransform((REAL)zoom, (REAL)zoom);
            DrawShapes(g, ed.shapes, bmp, POINT{ 0, 0 });
            if (shapeDrag) DrawShape(g, ed.draft, bmp, POINT{ 0, 0 });
            g.ResetTransform();
        }

        RECT client{ 0, 0, ww, wh };
        float sc = DpiScale(wnd);
        if (editing) {
            bar.Layout(client, client, TbMode::PinEdit, sc);
            bar.Draw(winDc, &ed, hover, TbMode::PinEdit);
        } else if (menuVisible) {
            menu.zoomPct = (int)(zoom * 100 + 0.5);
            menu.Layout(client, client, TbMode::PinHover, sc);
            menu.Draw(winDc, nullptr, hover, TbMode::PinHover);
        }

        PremultiplyBits(winBits, ww, wh);

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

void Close(PinWindow* p) {
    if (p->dead || !p->wnd) return;
    if (TextEntryActive()) CancelTextEntry();
    DestroyWindow(p->wnd);   // WM_DESTROY 里标记 dead + 请求 Prune
}

void ZoomAt(PinWindow* p, double factor, POINT cursorScr) {
    double nz = std::min(8.0, std::max(0.1, p->zoom * factor));
    if (nz == p->zoom) return;
    RECT wr; GetWindowRect(p->wnd, &wr);
    POINT anchor = p->ImgPt(cursorScr.x - wr.left, cursorScr.y - wr.top);
    p->zoom = nz;
    int nw = std::max(8, (int)(p->iw * nz) + 2);
    int nh = std::max(8, (int)(p->ih * nz) + 2);
    int nx = cursorScr.x - (int)(anchor.x * nz) - 1;
    int ny = cursorScr.y - (int)(anchor.y * nz) - 1;
    p->Render();
    SetWindowPos(p->wnd, HWND_TOPMOST, nx, ny, nw, nh, SWP_NOACTIVATE);
    p->Render();
}

void CycleAlpha(PinWindow* p) {
    static const BYTE steps[5] = { 255, 204, 153, 102, 51 };
    int i = 0;
    for (; i < 5; ++i) if (steps[i] == p->alpha) break;
    p->alpha = steps[(i + 1) % 5];
    p->Render();   // 重新走 ULW，把常量 alpha 烘进 BLENDFUNCTION
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
        p->editing = true; p->menuVisible = false;
        p->ed = Editor{};
        SetFocus(p->wnd);
        break;
    case TB_COPYIMG:
        BitmapToClipboard(p->wnd, p->img);
        Balloon(L"钉图 TackShot", L"已复制到剪贴板");
        break;
    case TB_SAVE:   SavePin(p); break;
    case TB_ZOOMIN: {
        RECT wr; GetWindowRect(p->wnd, &wr);
        ZoomAt(p, 1.25, { (wr.left + wr.right) / 2, (wr.top + wr.bottom) / 2 });
        break; }
    case TB_ZOOMOUT: {
        RECT wr; GetWindowRect(p->wnd, &wr);
        ZoomAt(p, 0.8, { (wr.left + wr.right) / 2, (wr.top + wr.bottom) / 2 });
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
    case WM_LBUTTONDOWN: {
        if (!p) break;
        SetFocus(wnd);
        int x = GET_X_LPARAM(lp), y = GET_Y_LPARAM(lp);
        if (!p->editing && p->menuVisible) {
            int id = p->menu.Hit(x, y);
            if (id) { OnMenu(p, id); break; }
        }
        if (p->editing) {
            int id = p->bar.Hit(x, y);
            if (id) { OnEditBar(p, id); break; }
            if (x >= 1 && y >= 1 && x < p->ww - 1 && y < p->wh - 1) {
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
                    p->ed.draft.a = ip; p->ed.draft.b = ip;
                    if (p->ed.cur == Tool::Pen) p->ed.draft.pts.push_back(ip);
                    p->Render();
                    break;
                }
            }
        }
        // 其余情况：拖动窗口
        ReleaseCapture();
        SendMessageW(wnd, WM_NCLBUTTONDOWN, HTCAPTION, 0);
        break; }
    case WM_MOUSEMOVE: {
        if (!p) break;
        KillTimer(wnd, TID_HIDE);
        int x = GET_X_LPARAM(lp), y = GET_Y_LPARAM(lp);
        int hov = 0;
        if (p->editing) hov = p->bar.Hit(x, y);
        else if (p->menuVisible) hov = p->menu.Hit(x, y);
        if (hov != p->hover) { p->hover = hov; p->Render(); }
        if (!p->menuVisible && !p->editing) SetTimer(wnd, TID_HOVER, 330, NULL);
        if (p->shapeDrag) {
            POINT ip = p->ImgPt(x, y);
            if (p->ed.cur == Tool::Pen) p->ed.draft.pts.push_back(ip);
            else p->ed.draft.b = ip;
            p->Render();
        }
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
            if (!p->editing) { p->menuVisible = false; p->Render(); }
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
    case WM_LBUTTONDBLCLK:
        if (p && !p->editing) Close(p);
        return 0;
    case WM_RBUTTONDOWN:
        if (!p) break;
        if (p->editing) { p->editing = false; p->shapeDrag = false; p->Render(); }
        else Close(p);
        return 0;
    case WM_MOUSEWHEEL: {
        if (!p) break;
        int delta = GET_WHEEL_DELTA_WPARAM(wp);
        POINT cp; GetCursorPos(&cp);
        if (GetKeyState(VK_CONTROL) & 0x8000) {
            int a = p->alpha + (delta > 0 ? 17 : -17);
            p->alpha = (BYTE)std::min(255, std::max(51, a));   // 20%–100%
            p->Render();
        } else {
            ZoomAt(p, delta > 0 ? 1.1 : (1.0 / 1.1), cp);
        }
        return 0; }
    case WM_KEYDOWN: {
        if (!p) break;
        if (TextEntryActive()) break;
        if (wp == VK_ESCAPE) {
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
    // 复制像素（源可能是任意 DIB）
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
    p->zoom = fit;

    int ww = (int)(iw * p->zoom) + 2, wh = (int)(ih * p->zoom) + 2;
    POINT cp; GetCursorPos(&cp);
    int x = cp.x - ww / 2, y = cp.y - 24;
    static int cascade = 0;
    x += (cascade % 5) * 18; y += (cascade % 5) * 18; cascade++;

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
