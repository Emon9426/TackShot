// capture.cpp — 屏幕捕获 + 框选遮罩 + 编辑宿主（区域截图主流程）
#include "editor.h"
#include <dwmapi.h>

namespace {

struct Session {
    HWND  wnd = nullptr;
    HBITMAP dib = nullptr;  void* bits = nullptr;      // 整个虚拟屏快照（32bpp top-down）
    int   vx = 0, vy = 0, vw = 0, vh = 0;              // 虚拟屏原点与尺寸（物理像素）
    Gdiplus::Bitmap* base = nullptr;                    // 供 DrawImage/马赛克采样
    HBITMAP back = nullptr; HDC backDc = nullptr; void* backBits = nullptr;
    int   phase = 0;        // 0=框选 1=编辑
    bool  lbtn = false;
    int   mode = 0;         // 0=新建选区 1=移动选区 2..9=控制点
    POINT down{}, selAnchor{};
    RECT  sel{}; bool selValid = false;
    Editor ed;
    Toolbar tb;
    int   hover = 0;
    ULONGLONG hoverSince = 0;
    HWND  prevFocus = nullptr;
    bool  shapeDrag = false;
    std::vector<RECT> snaps;   // 会话开始枚举的可见顶层窗口（EnumWindows 自顶向下 z 序）
    int   snapIdx = -1;
    ULONGLONG hintUntil = 0;   // 进入截图后的操作引导显示截止时刻
} s;

enum { TID_TIP = 3 };   // 悬停提示定时器：静止 320ms 后补一次重绘

// ---------------- 窗口吸附（FR-1.3）----------------
struct SnapEnumCtx { std::vector<RECT>* out; std::vector<std::wstring>* titles;
                     DWORD selfPid; RECT vi; };

BOOL CALLBACK SnapEnumProc(HWND hw, LPARAM lp) {
    SnapEnumCtx* c = (SnapEnumCtx*)lp;
    if (!IsWindowVisible(hw)) return TRUE;
    DWORD pid = 0; GetWindowThreadProcessId(hw, &pid);
    if (pid == c->selfPid) return TRUE;
    if (GetWindowLongW(hw, GWL_EXSTYLE) & WS_EX_TOOLWINDOW) return TRUE;
    if (GetWindowLongW(hw, GWL_STYLE) & WS_CHILD) return TRUE;
    if (GetShellWindow() == hw) return TRUE;
    int cloak = 0;
    if (SUCCEEDED(DwmGetWindowAttribute(hw, DWMWA_CLOAKED, &cloak, sizeof(cloak))) && cloak)
        return TRUE;
    RECT r{};
    if (FAILED(DwmGetWindowAttribute(hw, DWMWA_EXTENDED_FRAME_BOUNDS, &r, sizeof(r))))
        GetWindowRect(hw, &r);                       // 退化路径：含阴影
    if (r.right - r.left < 40 || r.bottom - r.top < 40) return TRUE;
    RECT is;
    if (!IntersectRect(&is, &r, &c->vi)) return TRUE;
    wchar_t title[64] = L"";
    GetWindowTextW(hw, title, 64);
    c->out->push_back(r);
    c->titles->push_back(title);
    return TRUE;
}

void BuildSnaps() {
    s.snaps.clear();
    s.snapIdx = -1;
    std::vector<std::wstring> titles;
    SnapEnumCtx ctx{ &s.snaps, &titles, GetCurrentProcessId(),
                     RECT{ s.vx, s.vy, s.vx + s.vw, s.vy + s.vh } };
    EnumWindows(SnapEnumProc, (LPARAM)&ctx);
    for (size_t i = 0; i < s.snaps.size() && i < 10; ++i) {
        RECT& r = s.snaps[i];
        Log(L"吸附候选%d: [%d,%d %ldx%ld] %s", (int)i + 1, r.left, r.top,
            r.right - r.left, r.bottom - r.top, titles[i].c_str());
    }
}

int FindSnap(POINT sp) {
    for (size_t i = 0; i < s.snaps.size(); ++i)
        if (PtInRect(&s.snaps[i], sp)) return (int)i;
    return -1;
}

RECT Local(RECT r) { OffsetRect(&r, -s.vx, -s.vy); return r; }
POINT LocalPt(POINT p) { return { p.x - s.vx, p.y - s.vy }; }
RECT NormSel(POINT a, POINT b) {
    RECT r{ std::min(a.x, b.x), std::min(a.y, b.y), std::max(a.x, b.x), std::max(a.y, b.y) };
    return r;
}

// 8 控制点：0..7 = 左上、上中、右上、右中、右下、下中、左下、左中
POINT HandlePt(const RECT& r, int i) {
    int cx = (r.left + r.right) / 2, cy = (r.top + r.bottom) / 2;
    static const int hx[8] = { 0,1,2,2,2,1,0,0 }, hy[8] = { 0,0,0,1,2,2,2,1 };
    int px[3] = { r.left, cx, r.right }, py[3] = { r.top, cy, r.bottom };
    return { px[hx[i]], py[hy[i]] };
}
int HitHandle(POINT p) {
    int r = (int)(10 * DpiScale(s.wnd) + 0.5f);
    for (int i = 0; i < 8; ++i) {
        POINT h = HandlePt(s.sel, i);
        if (abs(p.x - h.x) <= r && abs(p.y - h.y) <= r) return 2 + i;
    }
    return 0;
}
void ApplyHandle(RECT& r, int mode, POINT p) {
    int idx = mode - 2;
    if (idx == 0 || idx == 6 || idx == 7) r.left = std::min(p.x, r.right - 8);
    if (idx == 2 || idx == 3 || idx == 4) r.right = std::max(p.x, r.left + 8);
    if (idx == 0 || idx == 1 || idx == 2) r.top = std::min(p.y, r.bottom - 8);
    if (idx == 4 || idx == 5 || idx == 6) r.bottom = std::max(p.y, r.top + 8);
}
void ClampSel(RECT& r) {
    r.left = std::max<LONG>(r.left, (LONG)s.vx);
    r.top = std::max<LONG>(r.top, (LONG)s.vy);
    r.right = std::min<LONG>(r.right, (LONG)(s.vx + s.vw));
    r.bottom = std::min<LONG>(r.bottom, (LONG)(s.vy + s.vh));
    if (r.right < r.left + 8) r.right = r.left + 8;
    if (r.bottom < r.top + 8) r.bottom = r.top + 8;
}

void Invalidate() { InvalidateRect(s.wnd, NULL, FALSE); }

void LayoutBar() {
    RECT scr = { 0, 0, s.vw, s.vh };
    RECT host = Local(s.sel);
    s.tb.Layout(host, scr, TbMode::Editor, DpiScale(s.wnd));
}

void RenderTo(HDC hdc) {
    using namespace Gdiplus;
    if (!s.backDc) return;
    Graphics g(s.backDc);
    g.SetSmoothingMode(SmoothingModeAntiAlias);
    g.SetInterpolationMode(InterpolationModeHighQualityBicubic);

    g.DrawImage(s.base, Rect(0, 0, s.vw, s.vh), 0, 0, s.vw, s.vh, UnitPixel);
    SolidBrush dim(Color(118, 0, 0, 0));
    g.FillRectangle(&dim, 0, 0, s.vw, s.vh);

    // 窗口吸附预览（FR-1.3）：无选区时光标下窗口高亮（清晰原图+双描边），点击即整窗截取
    if (!s.selValid && s.snapIdx >= 0 && s.snapIdx < (int)s.snaps.size()) {
        RECT sr = s.snaps[s.snapIdx];
        ClampSel(sr);
        RECT sl = Local(sr);
        int w = sl.right - sl.left, h = sl.bottom - sl.top;
        POINT off{ sr.left - s.vx, sr.top - s.vy };
        g.DrawImage(s.base, Rect(sl.left, sl.top, w, h), off.x, off.y, w, h, UnitPixel);
        Pen ib(Color(255, 255, 255, 255), 1.f);
        Pen ob(Color(255, 59, 130, 246), 2.f);
        g.DrawRectangle(&ib, sl.left, sl.top, w - 1, h - 1);
        g.DrawRectangle(&ob, sl.left - 1, sl.top - 1, w + 1, h + 1);
        FontFamily ff(L"Segoe UI");
        Font f(&ff, (REAL)(12 * DpiScale(s.wnd)), FontStyleRegular, UnitPixel);
        wchar_t t[64];
        swprintf_s(t, 64, L"窗口 %d×%d（点击截取）", w, h);
        SolidBrush cb(Color(235, 15, 23, 42));
        RectF bb; StringFormat sf;
        g.MeasureString(t, -1, &f, PointF(0, 0), &sf, &bb);
        PointF org((REAL)sl.left, (REAL)(sl.top - 24 > 2 ? sl.top - 24 : sl.bottom + 6));
        g.FillRectangle(&cb, org.X - 4, org.Y - 2, bb.Width + 8, bb.Height + 4);
        SolidBrush tbx(Color(255, 226, 232, 240));
        g.DrawString(t, -1, &f, org, &tbx);
    }

    // 框选拖动中（含首次拖动，此时 selValid 尚为 false）也必须实时渲染选区（FR-1.11）
    bool draggingSel = (s.lbtn && s.mode == 0);
    if (s.selValid || draggingSel) {
        RECT sl = Local(s.sel);
        int w = sl.right - sl.left, h = sl.bottom - sl.top;
        POINT off{ s.sel.left - s.vx, s.sel.top - s.vy };
        g.DrawImage(s.base, Rect(sl.left, sl.top, w, h),
                    off.x, off.y, w, h, UnitPixel);
        if (draggingSel) {   // 拖动框选中：白色半透明高亮，与黑色遮罩强对比（FR-1.11）
            SolidBrush veil(Color(64, 255, 255, 255));
            g.FillRectangle(&veil, sl.left, sl.top, w, h);
        }
        g.TranslateTransform((REAL)sl.left, (REAL)sl.top);
        DrawShapes(g, s.ed.shapes, s.base, off);
        if (s.shapeDrag) DrawShape(g, s.ed.draft, s.base, off);
        g.ResetTransform();

        Pen bp(Color(255, 59, 130, 246), 1.6f);
        g.DrawRectangle(&bp, sl.left, sl.top, w, h);
        if (s.phase == 1) {
            int hs = (int)(10 * DpiScale(s.wnd) + 0.5f);   // 控制点边长（随 DPI 放大）
            int ho = hs / 2;
            SolidBrush hb(Color(255, 59, 130, 246));
            Pen hp(Color(255, 255, 255, 255), 1.2f);
            for (int i = 0; i < 8; ++i) {
                POINT p = HandlePt(s.sel, i);
                Rect hr(p.x - s.vx - ho, p.y - s.vy - ho, hs, hs);
                g.FillRectangle(&hb, hr);
                g.DrawRectangle(&hp, hr);
            }
        }
        // 拖动中显示坐标/尺寸
        if (s.lbtn && s.mode <= 1) {
            wchar_t t[64];
            swprintf_s(t, 64, L"%d, %d · %d×%d",
                       s.sel.left, s.sel.top,
                       s.sel.right - s.sel.left, s.sel.bottom - s.sel.top);
            FontFamily ff(L"Consolas");
            Font f(&ff, (REAL)(13 * DpiScale(s.wnd)), FontStyleRegular, UnitPixel);
            PointF org((REAL)sl.left, (REAL)(sl.top - 26 > 2 ? sl.top - 26 : sl.bottom + 6));
            SolidBrush cb(Color(235, 15, 23, 42));
            RectF bb; StringFormat sf;
            g.MeasureString(t, -1, &f, PointF(0, 0), &sf, &bb);
            g.FillRectangle(&cb, RectF(org.X - 4, org.Y - 2, bb.Width + 8, 18.f));
            SolidBrush tb_(Color(255, 226, 232, 240));
            g.DrawString(t, -1, &f, org, &tb_);
        }
    }

    if (s.phase == 1 && s.selValid) {
        LayoutBar();
        s.tb.Draw(s.backDc, &s.ed, s.hover, TbMode::Editor);
        if (s.hover && s.hoverSince && GetTickCount64() - s.hoverSince > 300) {
            POINT cp; GetCursorPos(&cp);
            cp = LocalPt(cp);
            DrawTooltip(g, cp, RECT{ 0, 0, s.vw, s.vh }, TbName(s.hover),
                        DpiScale(s.wnd));
        }
    }

    // 操作引导条：进入截图后短暂显示，说明"点击整窗 / 拖动自由框选"两种方式
    if (s.hintUntil && !s.selValid && GetTickCount64() < s.hintUntil) {
        const wchar_t* txt = L"移到窗口上点击 = 整窗截取　｜　拖动 = 自由框选　｜　Esc 取消";
        float sc = DpiScale(s.wnd);
        FontFamily ff(L"Segoe UI");
        Font f(&ff, 13.f * sc, FontStyleRegular, UnitPixel);
        RectF bb; StringFormat sf;
        g.MeasureString(txt, -1, &f, PointF(0, 0), &sf, &bb);
        REAL hx = (s.vw - bb.Width) / 2;
        REAL hy = (REAL)(int)(36 * sc);
        SolidBrush bg(Color(235, 15, 23, 42));
        g.FillRectangle(&bg, hx - 12, hy - 7, bb.Width + 24, bb.Height + 14);
        Pen bp(Color(255, 51, 65, 85), 1.f);
        g.DrawRectangle(&bp, hx - 12, hy - 7, bb.Width + 24, bb.Height + 14);
        SolidBrush tb(Color(255, 241, 245, 249));
        g.DrawString(txt, -1, &f, PointF(hx, hy), &tb);
    }

    // 调试转储：设置环境变量 TACKSHOT_DEBUG_SHOT=1 时，把每帧渲染结果落盘（自动化验证用）
    if (GetEnvironmentVariableW(L"TACKSHOT_DEBUG_SHOT", nullptr, 0) && s.back && s.backBits) {
        DWORD hdrlen = sizeof(BITMAPFILEHEADER) + sizeof(BITMAPINFOHEADER);
        DWORD rowsz = (DWORD)s.vw * 4;
        std::vector<BYTE> buf(hdrlen + rowsz * s.vh);
        BITMAPFILEHEADER* fh = (BITMAPFILEHEADER*)buf.data();
        BITMAPINFOHEADER* ih = (BITMAPINFOHEADER*)(buf.data() + sizeof(BITMAPFILEHEADER));
        fh->bfType = 0x4D42; fh->bfOffBits = hdrlen; fh->bfSize = (DWORD)buf.size();
        ih->biSize = sizeof(BITMAPINFOHEADER);
        ih->biWidth = s.vw; ih->biHeight = -s.vh;   // top-down
        ih->biPlanes = 1; ih->biBitCount = 32; ih->biCompression = BI_RGB;
        memcpy(buf.data() + hdrlen, s.backBits, (size_t)rowsz * s.vh);
        HANDLE df = CreateFileW((g_exeDir + L"\\dragframe.bmp").c_str(),
                                GENERIC_WRITE, 0, NULL, CREATE_ALWAYS,
                                FILE_ATTRIBUTE_NORMAL, NULL);
        if (df != INVALID_HANDLE_VALUE) {
            DWORD wr = 0;
            WriteFile(df, buf.data(), (DWORD)buf.size(), &wr, NULL);
            CloseHandle(df);
        }
    }

    BitBlt(hdc, 0, 0, s.vw, s.vh, s.backDc, 0, 0, SRCCOPY);
}

void EndSession(bool restoreFocus) {
    if (TextEntryActive()) CancelTextEntry();
    HWND w = s.wnd;
    s.wnd = nullptr;
    if (w) DestroyWindow(w);       // WM_DESTROY 释放资源
    if (restoreFocus && s.prevFocus) {
        SetForegroundWindow(s.prevFocus);
        s.prevFocus = nullptr;
    }
}

void Cancel() { EndSession(true); }

void Finish(HBITMAP out) {
    EndSession(true);
    FinishImage(out);
}

// 确认：把选区+标注合成为独立位图并输出
void Confirm() {
    if (!s.selValid) return;
    int w = s.sel.right - s.sel.left, h = s.sel.bottom - s.sel.top;
    void* ob = nullptr;
    HBITMAP out = CreateDib32(w, h, &ob);
    if (!out) { Cancel(); return; }
    HDC mdc = CreateCompatibleDC(NULL);
    HGDIOBJ old = SelectObject(mdc, out);
    {
        Gdiplus::Graphics g(mdc);
        g.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);
        POINT off{ s.sel.left - s.vx, s.sel.top - s.vy };
        g.DrawImage(s.base, Gdiplus::Rect(0, 0, w, h), off.x, off.y, w, h,
                    Gdiplus::UnitPixel);
        DrawShapes(g, s.ed.shapes, s.base, off);
    }
    SelectObject(mdc, old);
    DeleteDC(mdc);
    for (int i = 0; i < w * h; ++i) ((BYTE*)ob)[i * 4 + 3] = 0xFF;
    Finish(out);
}

void SaveAs() {
    if (!s.selValid) return;
    if (TextEntryActive()) CancelTextEntry();
    int w = s.sel.right - s.sel.left, h = s.sel.bottom - s.sel.top;
    void* ob = nullptr;
    HBITMAP out = CreateDib32(w, h, &ob);
    if (!out) return;
    HDC mdc = CreateCompatibleDC(NULL);
    HGDIOBJ old = SelectObject(mdc, out);
    {
        Gdiplus::Graphics g(mdc);
        POINT off{ s.sel.left - s.vx, s.sel.top - s.vy };
        g.DrawImage(s.base, Gdiplus::Rect(0, 0, w, h), off.x, off.y, w, h,
                    Gdiplus::UnitPixel);
        DrawShapes(g, s.ed.shapes, s.base, off);
    }
    SelectObject(mdc, old);
    DeleteDC(mdc);
    for (int i = 0; i < w * h; ++i) ((BYTE*)ob)[i * 4 + 3] = 0xFF;

    bool jpeg = g_cfg.format == L"jpeg";
    wchar_t file[MAX_PATH] = L"";
    wcsncpy_s(file, (NowStamp() + (jpeg ? L".jpg" : L".png")).c_str(), _TRUNCATE);
    OPENFILENAMEW ofn{};
    ofn.lStructSize = sizeof(ofn);
    ofn.hwndOwner = s.wnd;
    ofn.lpstrFile = file;
    ofn.nMaxFile = MAX_PATH;
    ofn.lpstrFilter = jpeg ? L"JPEG 图像\0*.jpg\0所有文件\0*.*\0"
                           : L"PNG 图像\0*.png\0所有文件\0*.*\0";
    ofn.nFilterIndex = 1;
    ofn.lpstrDefExt = jpeg ? L"jpg" : L"png";
    ofn.Flags = OFN_OVERWRITEPROMPT | OFN_PATHMUSTEXIST;
    std::wstring dir = g_cfg.output_dir.empty() ? DefaultSaveDir() : g_cfg.output_dir;
    ofn.lpstrInitialDir = dir.c_str();
    if (GetSaveFileNameW(&ofn)) {
        if (SaveBitmapToFile(out, file, jpeg, g_cfg.jpeg_quality))
            Balloon(L"钉图 TackShot", (L"已保存：" + std::wstring(file)).c_str());
        else
            Balloon(L"钉图 TackShot", L"保存失败：无法写入目标文件");
    }
    DeleteObject(out);
    Invalidate();
}

void OnToolbar(int id) {
    switch (id) {
    case TB_OK:     Confirm(); return;
    case TB_PIN:    Confirm(); return;      // 默认动作＝复制并贴图
    case TB_SAVE:   SaveAs(); return;
    case TB_CANCEL: Cancel(); return;
    case TB_RECT: s.ed.cur = Tool::Rect; break;
    case TB_ELLIPSE: s.ed.cur = Tool::Ellipse; break;
    case TB_LINE: s.ed.cur = Tool::Line; break;
    case TB_ARROW: s.ed.cur = Tool::Arrow; break;
    case TB_PEN: s.ed.cur = Tool::Pen; break;
    case TB_TEXT: s.ed.cur = Tool::Text; break;
    case TB_MOSAIC: s.ed.cur = Tool::Mosaic; break;
    case TB_HIGHLIGHT: s.ed.cur = Tool::Highlight; break;
    case TB_UNDO: s.ed.Undo(); break;
    case TB_REDO: s.ed.Redo(); break;
    case TB_C0: case TB_C1: case TB_C2:
    case TB_C3: case TB_C4: case TB_C5: {
        static const DWORD cols[6] = { 0xFFEF4444,0xFFF59E0B,0xFF22C55E,
                                       0xFF3B82F6,0xFFFFFFFF,0xFF111827 };
        s.ed.color.SetValue(cols[id - TB_C0]);
        break; }
    case TB_W0: s.ed.widthIdx = 0; break;
    case TB_W1: s.ed.widthIdx = 1; break;
    case TB_W2: s.ed.widthIdx = 2; break;
    default: break;
    }
    Invalidate();
}

void UpdateCursor(POINT lp) {
    static HCURSOR crossCur = nullptr;
    if (!crossCur) crossCur = CreateCrossCursor();
    HCURSOR cross = crossCur ? crossCur : LoadCursor(NULL, IDC_CROSS);
    HCURSOR c = LoadCursor(NULL, IDC_ARROW);
    if (s.tb.Hit(lp.x, lp.y)) c = LoadCursor(NULL, IDC_HAND);
    else if (!s.selValid && !s.lbtn && s.snapIdx >= 0) c = LoadCursor(NULL, IDC_ARROW);
    else if (!s.selValid || s.shapeDrag) c = cross;
    else if (HitHandle(lp)) c = LoadCursor(NULL, IDC_SIZEALL);
    else if (PtInRect(&s.sel, { lp.x + s.vx, lp.y + s.vy })) {
        if (s.ed.cur != Tool::None) c = cross;
        else c = LoadCursor(NULL, IDC_SIZEALL);
    }
    SetCursor(c);
}

LRESULT CALLBACK CapProc(HWND wnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_ERASEBKGND: return 1;
    case WM_PAINT: {
        PAINTSTRUCT ps;
        BeginPaint(wnd, &ps);
        RenderTo(ps.hdc);
        EndPaint(wnd, &ps);
        return 0; }
    case WM_SETCURSOR: {
        POINT p; GetCursorPos(&p);
        UpdateCursor(LocalPt(p));
        return TRUE; }
    case WM_MOUSEMOVE: {
        if (!s.wnd) break;
        int x = GET_X_LPARAM(lp), y = GET_Y_LPARAM(lp);
        POINT sp{ x + s.vx, y + s.vy };
        int hov = (s.phase == 1 && s.selValid) ? s.tb.Hit(x, y) : 0;
        if (hov != s.hover) {
            s.hover = hov; s.hoverSince = GetTickCount64();
            KillTimer(wnd, TID_TIP);
            // 静止悬停不产生重绘事件，需定时器在 320ms 后补一次重绘，提示条才会出现
            if (hov) SetTimer(wnd, TID_TIP, 320, NULL);
            Invalidate();
        }
        if (!s.lbtn && !s.selValid) {
            int idx = FindSnap(sp);
            if (idx != s.snapIdx) { s.snapIdx = idx; Invalidate(); }
        }
        if (s.lbtn) {
            if (s.mode == 0) { s.sel = NormSel(s.down, sp); ClampSel(s.sel); }
            else if (s.mode == 10) {
                // 吸附窗口按压后拖动超过阈值 → 转为自由框选
                if (abs(sp.x - s.down.x) + abs(sp.y - s.down.y) > (int)(6 * DpiScale(s.wnd))) {
                    s.mode = 0;
                    s.snapIdx = -1;
                    s.sel = NormSel(s.down, sp);
                    ClampSel(s.sel);
                }
            }
            else if (s.mode == 1) {
                OffsetRect(&s.sel, sp.x - s.down.x, sp.y - s.down.y);
                s.down = sp; ClampSel(s.sel);
            } else if (s.mode >= 2) {
                ApplyHandle(s.sel, s.mode, sp); ClampSel(s.sel);
            }
            if (s.shapeDrag) {
                POINT cp{ sp.x - s.sel.left, sp.y - s.sel.top };
                if (s.ed.cur == Tool::Pen) s.ed.draft.pts.push_back(cp);
                else s.ed.draft.b = cp;
            }
            Invalidate();
        }
        break; }
    case WM_LBUTTONDOWN: {
        if (!s.wnd) break;
        SetFocus(wnd);
        s.hintUntil = 0;                  // 一旦开始操作即收起引导条
        int x = GET_X_LPARAM(lp), y = GET_Y_LPARAM(lp);
        POINT sp{ x + s.vx, y + s.vy };
        if (s.phase == 1 && s.selValid) {
            int id = s.tb.Hit(x, y);
            if (id) { OnToolbar(id); break; }
        }
        s.lbtn = true; s.down = sp;
        SetCapture(wnd);
        if (!s.selValid) {
            if (s.snapIdx >= 0) s.mode = 10;              // 吸附窗口：点击确认 / 拖动取消
            else { s.mode = 0; s.sel = NormSel(sp, sp); }
        }
        else {
            int hh = HitHandle(sp);
            if (hh) s.mode = hh;
            else if (PtInRect(&s.sel, sp)) {
                if (s.ed.cur == Tool::Text) {
                    POINT cp{ sp.x - s.sel.left, sp.y - s.sel.top };
                    Gdiplus::ARGB col = s.ed.color.GetValue();
                    int fs = FontSizeFor(s.ed.widthIdx);
                    ReleaseCapture(); s.lbtn = false;
                    StartTextEntry(wnd, sp, fs, col, [cp, col, fs](const std::wstring& txt) {
                        if (!txt.empty() && s.wnd) {
                            Shape sh; sh.tool = Tool::Text;
                            sh.color = col; sh.fontSize = fs;
                            sh.a = cp; sh.text = txt;
                            s.ed.Push(sh);
                            Invalidate();
                        }
                    });
                    break;
                } else if (s.ed.cur != Tool::None) {
                    s.mode = -1;
                    s.shapeDrag = true;
                    POINT cp{ sp.x - s.sel.left, sp.y - s.sel.top };
                    s.ed.draft = Shape{};
                    s.ed.draft.tool = s.ed.cur;
                    s.ed.draft.color = s.ed.color.GetValue();
                    s.ed.draft.penW = (float)PenWidth(s.ed.widthIdx);
                    s.ed.draft.fontSize = FontSizeFor(s.ed.widthIdx);
                    s.ed.draft.a = cp; s.ed.draft.b = cp;
                    if (s.ed.cur == Tool::Pen) s.ed.draft.pts.push_back(cp);
                } else {
                    s.mode = 1;
                }
            } else {
                s.snapIdx = FindSnap(sp);            // 选区外重选时也允许直接吸附
                s.selValid = false;
                if (s.snapIdx >= 0) s.mode = 10;
                else { s.mode = 0; s.sel = NormSel(sp, sp); }
            }
        }
        Invalidate();
        break; }
    case WM_LBUTTONUP: {
        if (!s.lbtn) break;
        s.lbtn = false; ReleaseCapture();
        if (s.mode == 10) {
            if (s.snapIdx >= 0 && s.snapIdx < (int)s.snaps.size()) {
                s.sel = s.snaps[s.snapIdx];
                ClampSel(s.sel);
                s.selValid = true; s.phase = 1;
                Log(L"窗口吸附截取：%ld×%ld",
                    s.sel.right - s.sel.left, s.sel.bottom - s.sel.top);
            }
        } else if (s.mode == 0) {
            if (s.sel.right - s.sel.left >= 6 && s.sel.bottom - s.sel.top >= 6) {
                s.selValid = true; s.phase = 1;
            }
        } else if (s.mode >= 2) {
            ClampSel(s.sel);
        }
        if (s.shapeDrag) {
            s.shapeDrag = false;
            Shape& d = s.ed.draft;
            bool ok = d.tool == Tool::Pen ? d.pts.size() > 1
                     : (abs(d.b.x - d.a.x) + abs(d.b.y - d.a.y) > 4);
            if (ok) s.ed.Push(d);
            Invalidate();
        }
        s.mode = 0;
        Invalidate();
        break; }
    case WM_LBUTTONDBLCLK: {
        int x = GET_X_LPARAM(lp), y = GET_Y_LPARAM(lp);
        POINT sp{ x + s.vx, y + s.vy };
        if (s.selValid && s.ed.cur == Tool::None && PtInRect(&s.sel, sp)) Confirm();
        break; }
    case WM_RBUTTONDOWN:
        Cancel();
        break;
    case WM_TIMER:
        if (wp == TID_TIP) { KillTimer(wnd, TID_TIP); Invalidate(); }
        return 0;
    case WM_KEYDOWN: {
        if (TextEntryActive()) break;
        if (wp == VK_ESCAPE) { Cancel(); return 0; }
        if (wp == VK_RETURN) { if (s.selValid) { Confirm(); return 0; } }
        if (wp == 'Z' && (GetKeyState(VK_CONTROL) & 0x8000)) {
            if (GetKeyState(VK_SHIFT) & 0x8000) s.ed.Redo(); else s.ed.Undo();
            Invalidate(); return 0;
        }
        if (wp == 'Y' && (GetKeyState(VK_CONTROL) & 0x8000)) { s.ed.Redo(); Invalidate(); return 0; }
        Tool t;
        if (ToolFromKey((UINT)wp, t)) { s.ed.cur = t; Invalidate(); return 0; }
        break; }
    case WM_DESTROY: {
        if (s.backDc) { DeleteDC(s.backDc); s.backDc = nullptr; }
        if (s.back) { DeleteObject(s.back); s.back = nullptr; }
        if (s.base) { delete s.base; s.base = nullptr; }
        if (s.dib) { DeleteObject(s.dib); s.dib = nullptr; }
        s.bits = s.backBits = nullptr;
        s.selValid = false; s.phase = 0; s.lbtn = false; s.shapeDrag = false;
        s.snaps.clear(); s.snapIdx = -1;
        break; }
    }
    return DefWindowProcW(wnd, msg, wp, lp);
}

const wchar_t* kCls = L"TackShotCapture";

struct CapEnumCtx { HDC mdc; HDC sdc; int vx; int vy; };

BOOL CALLBACK CapEnumMon(HMONITOR, HDC, LPRECT r, LPARAM lp) {
    CapEnumCtx* c = (CapEnumCtx*)lp;
    int w = r->right - r->left, h = r->bottom - r->top;
    BitBlt(c->mdc, r->left - c->vx, r->top - c->vy, w, h,
           c->sdc, r->left, r->top, SRCCOPY | CAPTUREBLT);
    return TRUE;
}

bool CaptureScreens() {
    s.vx = GetSystemMetrics(SM_XVIRTUALSCREEN);
    s.vy = GetSystemMetrics(SM_YVIRTUALSCREEN);
    s.vw = GetSystemMetrics(SM_CXVIRTUALSCREEN);
    s.vh = GetSystemMetrics(SM_CYVIRTUALSCREEN);
    if (s.vw <= 0 || s.vh <= 0) return false;
    HDC sdc = GetDC(NULL);
    s.dib = CreateDib32(s.vw, s.vh, &s.bits);
    if (!s.dib) { ReleaseDC(NULL, sdc); return false; }
    HDC mdc = CreateCompatibleDC(sdc);
    HGDIOBJ old = SelectObject(mdc, s.dib);
    CapEnumCtx ctx{ mdc, sdc, s.vx, s.vy };
    EnumDisplayMonitors(NULL, NULL, CapEnumMon, (LPARAM)&ctx);
    SelectObject(mdc, old);
    DeleteDC(mdc);
    ReleaseDC(NULL, sdc);
    // GDI BitBlt 不写 alpha——强制不透明，后续 GDI+ 混合才正确
    for (int i = 0; i < s.vw * s.vh; ++i) ((BYTE*)s.bits)[i * 4 + 3] = 0xFF;
    s.base = new Gdiplus::Bitmap(s.dib, NULL);
    return true;
}

HBITMAP WholeSnapshot() {
    // 全屏截图：复制一份并把 alpha 置满（bitblt 已置过，二次保险）
    if (!CaptureScreens()) return NULL;
    void* ob = nullptr;
    HBITMAP out = CreateDib32(s.vw, s.vh, &ob);
    memcpy(ob, s.bits, (size_t)s.vw * s.vh * 4);
    delete s.base; s.base = nullptr;
    DeleteObject(s.dib); s.dib = nullptr; s.bits = nullptr;
    return out;
}

} // namespace

void RegisterCaptureClass(HINSTANCE h) {
    WNDCLASSW wc{};
    wc.lpfnWndProc = CapProc;
    wc.hInstance = h;
    wc.lpszClassName = kCls;
    wc.style = CS_DBLCLKS;
    RegisterClassW(&wc);
}

void StartRegionCapture() {
    if (s.wnd) return;   // 会话进行中，忽略重入
    if (!CaptureScreens()) { Balloon(L"钉图 TackShot", L"截图失败：无法捕获屏幕"); return; }

    s.back = CreateDib32(s.vw, s.vh, &s.backBits);
    s.backDc = CreateCompatibleDC(NULL);
    SelectObject(s.backDc, s.back);

    s.phase = 0; s.selValid = false; s.lbtn = false; s.mode = 0;
    s.hover = 0; s.shapeDrag = false; s.hoverSince = 0;
    s.hintUntil = GetTickCount64() + 2800;
    s.ed = Editor{};
    s.prevFocus = GetForegroundWindow();
    BuildSnaps();

    s.wnd = CreateWindowExW(WS_EX_TOPMOST | WS_EX_TOOLWINDOW,
                            kCls, L"", WS_POPUP,
                            s.vx, s.vy, s.vw, s.vh,
                            NULL, NULL, g_inst, NULL);
    ShowWindow(s.wnd, SW_SHOW);
    UpdateWindow(s.wnd);
    SetForegroundWindow(s.wnd);
    SetFocus(s.wnd);
    Log(L"区域截图开始：虚拟屏 %dx%d @(%d,%d)，吸附候选窗口 %d 个",
        s.vw, s.vh, s.vx, s.vy, (int)s.snaps.size());
}

void StartFullscreenCapture() {
    if (s.wnd) return;
    HBITMAP snap = WholeSnapshot();
    if (!snap) { Balloon(L"钉图 TackShot", L"截图失败：无法捕获屏幕"); return; }
    Log(L"全屏截图完成：%dx%d", s.vw, s.vh);
    FinishImage(snap);
}
