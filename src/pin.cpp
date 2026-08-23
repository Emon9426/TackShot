// pin.cpp — 贴图窗口：置顶分层窗口 / 滚轮+边缘拖拽缩放 / 透明度 / 悬浮菜单 / 就地编辑
// V1.10：zoomX/zoomY 分离（四角等比、四边单轴）；顶部预留工具条悬浮区（不遮挡图片，
//        空白条区点击穿透 HTTRANSPARENT）；图片边缘 8 向拖拽调整尺寸。
#include "editor.h"

namespace {

const UINT WM_PRUNE = WM_APP + 3;
enum { TID_HOVER = 1, TID_HIDE = 2, TID_TIP = 3 };
const wchar_t* kPinCls = L"TackShotPin";
const int BORDER = 2;      // 贴图黑色边框宽度
const int STRIP_GAP = 6;   // 工具条与图片间距（逻辑 px）

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
        if (editing) {
            bar.Layout(strip, strip, TbMode::PinEdit, sc);
            bar.Draw(winDc, &ed, hover, TbMode::PinEdit);
        } else if (menuVisible) {
            menu.zoomPct = (int)(zoomX * 100 + 0.5);
            menu.Layout(strip, strip, TbMode::PinHover, sc);
            menu.Draw(winDc, nullptr, hover, TbMode::PinHover);
        }
        if (hover && hoverSince && GetTickCount64() - hoverSince > 300) {
            POINT cp; GetCursorPos(&cp);
            RECT wr; GetWindowRect(wnd, &wr);
            DrawTooltip(g, { cp.x - wr.left, cp.y - wr.top },
                        RECT{ 0, 0, ww, wh }, TbName(hover), sc);
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
            if (onBar) return HTCLIENT;
            return HTTRANSPARENT;
        }
        break; }
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
            if (hov) SetTimer(wnd, TID_TIP, 320, NULL);
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
            if (!p->editing) { p->menuVisible = false; p->Render(); }
        } else if (wp == TID_TIP) {
            KillTimer(wnd, TID_TIP);
            if (p->hover) p->Render();
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
