// editor.cpp — 标注对象绘制 + 工具条布局/渲染/命中
#include "editor.h"

using namespace Gdiplus;

int PenWidth(int idx) { static const int w[3] = { 2, 4, 7 }; return w[idx % 3]; }
int FontSizeFor(int idx) { static const int s[3] = { 18, 26, 36 }; return s[idx % 3]; }

static Rect NormRect(int x1, int y1, int x2, int y2) {
    return Rect(std::min(x1, x2), std::min(y1, y2),
                std::abs(x2 - x1), std::abs(y2 - y1));
}

void DrawShape(Graphics& g, const Shape& s, Bitmap* base, POINT baseOff) {
    Color c(s.color);
    Pen pen(c, s.penW);
    pen.SetLineCap(LineCapRound, LineCapRound, DashCapRound);
    int x1 = s.a.x, y1 = s.a.y, x2 = s.b.x, y2 = s.b.y;
    Rect rc = NormRect(x1, y1, x2, y2);
    switch (s.tool) {
    case Tool::Rect:
        g.DrawRectangle(&pen, rc);
        break;
    case Tool::Ellipse:
        g.DrawEllipse(&pen, rc);
        break;
    case Tool::Line:
        g.DrawLine(&pen, x1, y1, x2, y2);
        break;
    case Tool::Arrow: {
        g.DrawLine(&pen, x1, y1, x2, y2);
        float ang = atan2f((float)(y2 - y1), (float)(x2 - x1));
        float head = std::max(10.f, s.penW * 3.2f);
        PointF tip((REAL)x2, (REAL)y2);
        PointF l(tip.X - head * cosf(ang - 0.42f), tip.Y - head * sinf(ang - 0.42f));
        PointF r(tip.X - head * cosf(ang + 0.42f), tip.Y - head * sinf(ang + 0.42f));
        GraphicsPath p; p.AddLine(l, tip); p.AddLine(tip, r); p.CloseFigure();
        SolidBrush b(c); g.FillPath(&b, &p);
        break;
    }
    case Tool::Pen: {
        if (s.pts.size() == 1) {
            SolidBrush b(c);
            g.FillEllipse(&b, s.pts[0].x - s.penW / 2.f, s.pts[0].y - s.penW / 2.f, s.penW, s.penW);
        } else if (s.pts.size() > 1) {
            std::vector<PointF> pf;
            pf.reserve(s.pts.size());
            for (auto& p : s.pts) pf.push_back(PointF((REAL)p.x, (REAL)p.y));
            g.DrawCurve(&pen, pf.data(), (INT)pf.size());
        }
        break;
    }
    case Tool::Text: {
        if (s.text.empty()) break;
        FontFamily ff(L"Segoe UI");
        Font f(&ff, (REAL)s.fontSize, FontStyleRegular, UnitPixel);
        SolidBrush b(c);
        g.DrawString(s.text.c_str(), -1, &f, PointF((REAL)s.a.x, (REAL)s.a.y - s.fontSize * 0.8f), &b);
        break;
    }
    case Tool::Mosaic: {
        if (!base) break;
        int bw = (int)base->GetWidth(), bh = (int)base->GetHeight();
        int blk = std::max(4, (int)(s.penW * 5.f));
        for (int y = rc.Y; y < rc.Y + rc.Height; y += blk) {
            for (int x = rc.X; x < rc.X + rc.Width; x += blk) {
                int sx = baseOff.x + x + blk / 2; if (sx >= bw) sx = bw - 1; if (sx < 0) sx = 0;
                int sy = baseOff.y + y + blk / 2; if (sy >= bh) sy = bh - 1; if (sy < 0) sy = 0;
                Color pc; base->GetPixel(sx, sy, &pc);
                SolidBrush b(pc);
                int w = std::min(blk, rc.X + rc.Width - x);
                int h = std::min(blk, rc.Y + rc.Height - y);
                if (w > 0 && h > 0) g.FillRectangle(&b, x, y, w, h);
            }
        }
        break;
    }
    case Tool::Highlight: {
        Color ht(80, c.GetR(), c.GetG(), c.GetB());
        SolidBrush b(ht);
        g.FillRectangle(&b, rc);
        break;
    }
    default: break;
    }
}

void DrawShapes(Graphics& g, const std::vector<Shape>& v, Bitmap* base, POINT baseOff) {
    for (auto& s : v) DrawShape(g, s, base, baseOff);
}

bool ToolFromKey(UINT vk, Tool& t) {
    switch (vk) {
    case 'R': t = Tool::Rect; return true;
    case 'O': t = Tool::Ellipse; return true;
    case 'L': t = Tool::Line; return true;
    case 'A': t = Tool::Arrow; return true;
    case 'B': t = Tool::Pen; return true;
    case 'T': t = Tool::Text; return true;
    case 'M': t = Tool::Mosaic; return true;
    case 'H': t = Tool::Highlight; return true;
    default: return false;
    }
}

const wchar_t* ToolDisplayName(Tool t) {
    switch (t) {
    case Tool::Rect: return L"矩形"; case Tool::Ellipse: return L"椭圆";
    case Tool::Line: return L"直线"; case Tool::Arrow: return L"箭头";
    case Tool::Pen: return L"画笔"; case Tool::Text: return L"文字";
    case Tool::Mosaic: return L"马赛克"; case Tool::Highlight: return L"高亮";
    default: return L"-";
    }
}

// ================= 工具条 =================
static const DWORD TB_COLORS[6] = {
    0xFFEF4444, 0xFFF59E0B, 0xFF22C55E, 0xFF3B82F6, 0xFFFFFFFF, 0xFF111827
};

void Toolbar::Layout(const RECT& host, const RECT& scr, TbMode mode, float dpiScale) {
    scale = std::max(1.0f, dpiScale);
    auto S = [this](float v) { return (int)(v * scale + 0.5f); };
    btns.clear();
    struct Item { int id; float w; };
    std::vector<Item> items;
    if (mode == TbMode::Editor) {
        items = { {TB_OK,16},{TB_PIN,16},{TB_SAVE,16},{TB_CANCEL,16},
                  {TB_RECT,16},{TB_ELLIPSE,16},{TB_LINE,16},{TB_ARROW,16},{TB_PEN,16},
                  {TB_TEXT,16},{TB_MOSAIC,16},{TB_HIGHLIGHT,16},
                  {TB_UNDO,16},{TB_REDO,16},
                  {TB_C0,12},{TB_C1,12},{TB_C2,12},{TB_C3,12},{TB_C4,12},{TB_C5,12},
                  {TB_W0,12},{TB_W1,12},{TB_W2,12} };
    } else if (mode == TbMode::PinEdit) {
        items = { {TB_OK,16},{TB_CANCEL,16},
                  {TB_RECT,16},{TB_ELLIPSE,16},{TB_LINE,16},{TB_ARROW,16},{TB_PEN,16},
                  {TB_TEXT,16},{TB_MOSAIC,16},{TB_HIGHLIGHT,16},
                  {TB_UNDO,16},{TB_REDO,16},
                  {TB_C0,12},{TB_C1,12},{TB_C2,12},{TB_C3,12},{TB_C4,12},{TB_C5,12},
                  {TB_W0,12},{TB_W1,12},{TB_W2,12} };
    } else { // PinHover
        items = { {TB_EDIT,16},{TB_COPYIMG,16},{TB_SAVE,16},
                  {TB_ZOOMOUT,12},{TB_NONE,26},{TB_ZOOMIN,12},
                  {TB_OPAQUE,16},{TB_CLOSE,16} };
    }
    int gap = S(1), pad = S(3);
    int total = pad * 2;
    for (size_t i = 0; i < items.size(); ++i)
        total += S(items[i].w) + (i + 1 < items.size() ? gap : 0);
    int h = S(20);
    int cx = host.left + (host.right - host.left) / 2;
    int x = cx - total / 2;
    int y;
    if (mode == TbMode::PinHover) {
        y = host.top + S(4);                   // 贴图顶部内侧
    } else {
        y = host.bottom + S(8);                // 选区下方，越界翻到上方
        if (y + h > scr.bottom) y = host.top - S(8) - h;
        if (y < scr.top) y = scr.top;
    }
    if (x + total > scr.right - S(4)) x = scr.right - S(4) - total;
    if (x < scr.left + S(4)) x = scr.left + S(4);
    bar = { x, y, x + total, y + h };
    int cur = x + pad;
    for (auto& it : items) {
        btns.push_back({ it.id, { cur, y + S(2), cur + S(it.w), y + h - S(2) } });
        cur += S(it.w) + gap;
    }
}
int Toolbar::Hit(int x, int y) const {
    if (!PtInRect(&bar, { x, y })) return 0;
    for (auto& b : btns)
        if (PtInRect(&b.r, { x, y })) return b.id;
    return 0;
}

// ---- 矢量图标绘制（不依赖字体符号，任何环境一致） ----
static void Glyph(Graphics& g, int id, const RectF& r, const Editor* ed) {
    REAL w = r.Width, h = r.Height;
    REAL m = w * 0.2f;
    REAL l = r.X + m, rt = r.X + w - m;
    REAL tp = r.Y + m, bm = r.Y + h - m;
    REAL mx = r.X + w / 2, my = r.Y + h / 2;
    float pw = h * 0.16f;      // 主笔画
    float pwT = h * 0.22f;     // 加粗笔画（确认/取消/画笔）
    auto pen = [&](ARGB c, float thick) {
        // 堆上懒创建、永不析构：避免 static GDI+ 对象在 GdiplusShutdown 后析构导致退出崩溃
        static Pen* p = nullptr;
        if (!p) p = new Pen(Color(c), thick);
        p->SetColor(Color(c)); p->SetWidth(thick);
        p->SetLineCap(LineCapRound, LineCapRound, DashCapRound);
        return p;
    };
    switch (id) {
    case TB_OK:
        g.DrawLine(pen(0xFF22C55E, pwT), l, my + h * 0.05f, mx - w * 0.06f, bm);
        g.DrawLine(pen(0xFF22C55E, pwT), mx - w * 0.06f, bm, rt, tp);
        break;
    case TB_PIN: {
        g.DrawLine(pen(0xFF94A3B8, pw * 0.8f), mx, my + h * 0.05f, mx + w * 0.14f, bm);
        SolidBrush b(0xFF3B82F6);
        g.FillEllipse(&b, mx - w * 0.2f, tp, w * 0.44f, h * 0.44f);
        g.DrawEllipse(pen(0xFFFFFFFF, pw * 0.45f), mx - w * 0.2f, tp, w * 0.44f, h * 0.44f);
        break; }
    case TB_SAVE: {
        g.DrawLine(pen(0xFFF59E0B, pw), mx, tp, mx, my + h * 0.04f);
        GraphicsPath tri;
        tri.AddLine(mx - w * 0.14f, my - h * 0.02f, mx, my + h * 0.12f);
        tri.AddLine(mx, my + h * 0.12f, mx + w * 0.14f, my - h * 0.02f);
        tri.CloseFigure();
        SolidBrush ab(0xFFF59E0B); g.FillPath(&ab, &tri);
        g.DrawLine(pen(0xFF94A3B8, pw * 0.7f), l, bm, rt, bm);
        break; }
    case TB_CANCEL:
        g.DrawLine(pen(0xFFEF4444, pwT), l, tp, rt, bm);
        g.DrawLine(pen(0xFFEF4444, pwT), l, bm, rt, tp);
        break;
    case TB_RECT:
        g.DrawRectangle(pen(0xFF3B82F6, pw), r.X + w * 0.18f, r.Y + h * 0.24f, w * 0.64f, h * 0.52f);
        break;
    case TB_ELLIPSE:
        g.DrawEllipse(pen(0xFF14B8A6, pw), r.X + w * 0.16f, r.Y + h * 0.22f, w * 0.68f, h * 0.56f);
        break;
    case TB_LINE:
        g.DrawLine(pen(0xFF94A3B8, pw), l, bm, rt, tp);
        break;
    case TB_ARROW: {
        REAL ex = rt - w * 0.16f, ey = tp + h * 0.16f;
        g.DrawLine(pen(0xFF6366F1, pw), l, bm, ex, ey);
        GraphicsPath hd;
        hd.AddLine(ex - w * 0.04f, ey - h * 0.22f, ex, ey);
        hd.AddLine(ex, ey, ex + w * 0.22f, ey - h * 0.04f);
        hd.CloseFigure();
        SolidBrush hb(0xFF6366F1); g.FillPath(&hb, &hd);
        break; }
    case TB_PEN: {
        PointF pts[4] = { {l, my}, {mx - w * 0.14f, tp + h * 0.02f},
                          {mx + w * 0.14f, bm - h * 0.02f}, {rt, my - h * 0.08f} };
        g.DrawCurve(pen(0xFFF97316, pwT), pts, 4);
        break; }
    case TB_TEXT: {
        FontFamily ff(L"Segoe UI");
        Font f(&ff, h * 0.68f, FontStyleBold, UnitPixel);
        SolidBrush b(0xFF8B5CF6);
        StringFormat sf; sf.SetAlignment(StringAlignmentCenter);
        RectF rr(r.X, r.Y - h * 0.04f, w, h);
        g.DrawString(L"T", 1, &f, rr, &sf, &b);
        break; }
    case TB_MOSAIC: {
        float cw = w * 0.62f / 3, ch = h * 0.62f / 3, gp = cw * 0.2f;
        float ox = r.X + w * 0.19f, oy = r.Y + h * 0.19f;
        for (int yy = 0; yy < 3; ++yy)
            for (int xx = 0; xx < 3; ++xx) {
                SolidBrush b(((xx + yy) & 1) ? 0xFFA5B4C4 : 0xFF64748B);
                g.FillRectangle(&b, ox + xx * (cw + gp), oy + yy * (ch + gp), cw, ch);
            }
        break; }
    case TB_HIGHLIGHT: {
        RectF hr(r.X + w * 0.2f, r.Y + h * 0.26f, w * 0.6f, h * 0.48f);
        SolidBrush b(Color(200, 250, 204, 21));
        g.FillRectangle(&b, hr);
        g.DrawRectangle(pen(0xFFF59E0B, pw * 0.55f), hr);
        break; }
    case TB_UNDO: {
        g.DrawArc(pen(0xFF94A3B8, pw), r.X + w * 0.2f, r.Y + h * 0.18f, w * 0.6f, h * 0.6f, 180.f, 240.f);
        SolidBrush b(0xFF94A3B8);
        g.FillEllipse(&b, r.X + w * 0.2f - pw * 0.45f, r.Y + h * 0.18f - pw * 0.25f, pw * 0.9f, pw * 0.9f);
        g.DrawLine(pen(0xFF94A3B8, pw), r.X + w * 0.2f, r.Y + h * 0.18f, r.X + w * 0.44f, r.Y + h * 0.18f);
        break; }
    case TB_REDO: {
        g.DrawArc(pen(0xFF94A3B8, pw), r.X + w * 0.2f, r.Y + h * 0.18f, w * 0.6f, h * 0.6f, -60.f, 240.f);
        SolidBrush b(0xFF94A3B8);
        g.FillEllipse(&b, rt - pw * 0.45f, r.Y + h * 0.18f - pw * 0.25f, pw * 0.9f, pw * 0.9f);
        g.DrawLine(pen(0xFF94A3B8, pw), rt, r.Y + h * 0.18f, r.X + w * 0.56f, r.Y + h * 0.18f);
        break; }
    case TB_C0: case TB_C1: case TB_C2: case TB_C3: case TB_C4: case TB_C5: {
        static const DWORD TB_COLORS2[6] = {
            0xFFEF4444, 0xFFF59E0B, 0xFF22C55E, 0xFF3B82F6, 0xFFFFFFFF, 0xFF111827
        };
        Color c(TB_COLORS2[id - TB_C0]);
        Color fill(c.GetA(), c.GetR(), c.GetG(), c.GetB());
        if (TB_COLORS2[id - TB_C0] == 0xFF111827) fill = Color(255, 40, 48, 64);
        SolidBrush b(fill);
        REAL d = r.Width * 0.72f;
        g.FillEllipse(&b, r.X + (r.Width - d) / 2, r.Y + (r.Height - d) / 2, d, d);
        if (ed && ed->color.GetValue() == c.GetValue()) {
            Pen ring(0xFF60A5FA, 2.0f);
            g.DrawEllipse(&ring, r.X + (r.Width - d) / 2 - 2, r.Y + (r.Height - d) / 2 - 2, d + 4, d + 4);
        }
        break; }
    case TB_W0: case TB_W1: case TB_W2: {
        int idx = id - TB_W0;
        g.DrawLine(pen(0xFFE2E8F0, (REAL)PenWidth(idx)), l, my, rt, my);
        break; }
    case TB_EDIT: {
        g.DrawLine(pen(0xFFF59E0B, pwT), r.X + w * 0.3f, bm, rt - w * 0.08f, tp + h * 0.08f);
        g.DrawLine(pen(0xFF94A3B8, pw * 0.55f), r.X + w * 0.18f, bm - h * 0.12f, r.X + w * 0.3f, bm);
        g.DrawLine(pen(0xFF94A3B8, pw * 0.55f), r.X + w * 0.3f, bm, r.X + w * 0.44f, bm - h * 0.12f);
        break; }
    case TB_COPYIMG: {
        g.DrawRectangle(pen(0xFF0EA5E9, pw * 0.7f), r.X + w * 0.34f, tp, w * 0.46f, h * 0.46f);
        g.DrawRectangle(pen(0xFF0EA5E9, pw * 0.7f), l, r.Y + h * 0.34f, w * 0.46f, h * 0.46f);
        break; }
    case TB_ZOOMOUT: case TB_ZOOMIN: {
        float d = w * 0.62f;
        RectF mg(r.X + w * 0.08f, r.Y + h * 0.08f, d, d);
        g.DrawEllipse(pen(0xFF0EA5E9, pw * 0.8f), mg);
        g.DrawLine(pen(0xFF0EA5E9, pw * 0.8f), r.X + w * 0.52f, r.Y + h * 0.52f, rt, bm);
        REAL cxx = r.X + w * 0.08f + d / 2, cyy = r.Y + h * 0.08f + d / 2;
        g.DrawLine(pen(0xFFE2E8F0, pw * 0.7f), cxx - w * 0.13f, cyy, cxx + w * 0.13f, cyy);
        if (id == TB_ZOOMIN) g.DrawLine(pen(0xFFE2E8F0, pw * 0.7f), cxx, cyy - w * 0.13f, cxx, cyy + w * 0.13f);
        break; }
    case TB_OPAQUE: {
        float d = w * 0.66f;
        RectF cr(r.X + w * 0.17f, r.Y + h * 0.17f, d, d);
        SolidBrush b(0xFF3B82F6);
        g.FillPie(&b, cr, -90.f, 180.f);
        g.DrawEllipse(pen(0xFF93C5FD, pw * 0.6f), cr);
        break; }
    case TB_CLOSE:
        g.DrawLine(pen(0xFFEF4444, pwT), l, tp, rt, bm);
        g.DrawLine(pen(0xFFEF4444, pwT), l, bm, rt, tp);
        break;
    default: break;
    }
}
void Toolbar::Draw(HDC dc, const Editor* ed, int hover, TbMode mode) const {
    if (btns.empty()) return;
    Graphics g(dc);
    g.SetSmoothingMode(SmoothingModeAntiAlias);
    // 深色圆角面板
    RectF br((REAL)bar.left, (REAL)bar.top,
             (REAL)(bar.right - bar.left), (REAL)(bar.bottom - bar.top));
    GraphicsPath bg;
    REAL rad = 9 * scale;
    bg.AddArc(br.X, br.Y, rad * 2, rad * 2, 180, 90);
    bg.AddArc(br.GetRight() - rad * 2, br.Y, rad * 2, rad * 2, 270, 90);
    bg.AddArc(br.GetRight() - rad * 2, br.GetBottom() - rad * 2, rad * 2, rad * 2, 0, 90);
    bg.AddArc(br.X, br.GetBottom() - rad * 2, rad * 2, rad * 2, 90, 90);
    bg.CloseFigure();
    SolidBrush panelB(Color(235, 27, 33, 44));
    g.FillPath(&panelB, &bg);
    Pen panelP(Color(255, 51, 65, 85), 1.f);
    g.DrawPath(&panelP, &bg);

    Tool curTool = ed ? ed->cur : Tool::None;
    int curW = ed ? ed->widthIdx : 1;

    for (auto& b : btns) {
        RectF r((REAL)b.r.left + 2, (REAL)b.r.top + 2,
                (REAL)(b.r.right - b.r.left) - 4, (REAL)(b.r.bottom - b.r.top) - 4);
        if (b.id == TB_NONE) {   // 缩放百分比文本
            wchar_t t[16];
            swprintf_s(t, 16, L"%d%%", zoomPct);
            FontFamily ff(L"Segoe UI");
            Font f(&ff, 8.5f * scale, FontStyleRegular, UnitPixel);
            SolidBrush tb(Color(255, 148, 163, 184));
            StringFormat sf; sf.SetAlignment(StringAlignmentCenter);
            g.DrawString(t, -1, &f, RectF(r.X - 2, r.Y, r.Width + 4, r.Height), &sf, &tb);
            continue;
        }
        bool active = false;
        if (ed) {
            if (b.id == TB_RECT) active = curTool == Tool::Rect;
            else if (b.id == TB_ELLIPSE) active = curTool == Tool::Ellipse;
            else if (b.id == TB_LINE) active = curTool == Tool::Line;
            else if (b.id == TB_ARROW) active = curTool == Tool::Arrow;
            else if (b.id == TB_PEN) active = curTool == Tool::Pen;
            else if (b.id == TB_TEXT) active = curTool == Tool::Text;
            else if (b.id == TB_MOSAIC) active = curTool == Tool::Mosaic;
            else if (b.id == TB_HIGHLIGHT) active = curTool == Tool::Highlight;
        }
        if (b.id == TB_W0) active = curW == 0;
        else if (b.id == TB_W1) active = curW == 1;
        else if (b.id == TB_W2) active = curW == 2;
        bool hov = (hover == b.id);
        if (active) { SolidBrush ab(Color(255, 37, 99, 235)); g.FillRectangle(&ab, r); }
        else if (hov) { SolidBrush hb(Color(70, 51, 65, 85)); g.FillRectangle(&hb, r); }
        Glyph(g, b.id, r, ed);
    }
}

// ================= 文字输入弹窗 =================
namespace {
    HWND g_textWnd = nullptr;
    HWND g_textOwner = nullptr;
    std::function<void(const std::wstring&)> g_textCommit;

    LRESULT CALLBACK TextProc(HWND wnd, UINT msg, WPARAM wp, LPARAM lp,
                              UINT_PTR id, DWORD_PTR) {
        switch (msg) {
        case WM_NCDESTROY: g_textWnd = nullptr; break;
        case WM_KEYDOWN:
            if (wp == VK_RETURN) {
                wchar_t buf[512];
                int n = GetWindowTextW(wnd, buf, 512);
                auto cb = g_textCommit; HWND owner = g_textOwner;
                g_textCommit = nullptr;
                DestroyWindow(wnd);
                if (owner) SetFocus(owner);
                if (cb && n > 0) cb(std::wstring(buf, n));
                return 0;
            }
            if (wp == VK_ESCAPE) {
                g_textCommit = nullptr;
                HWND owner = g_textOwner;
                DestroyWindow(wnd);
                if (owner) SetFocus(owner);
                return 0;
            }
            break;
        case WM_KILLFOCUS:
            if (g_textCommit) {
                wchar_t buf[512];
                int n = GetWindowTextW(wnd, buf, 512);
                auto cb = g_textCommit;
                g_textCommit = nullptr;
                DestroyWindow(wnd);
                if (cb && n > 0) cb(std::wstring(buf, n));
                return 0;
            }
            break;
        }
        return DefSubclassProc(wnd, msg, wp, lp);
    }
}

bool TextEntryActive() { return g_textWnd != nullptr; }

void CancelTextEntry() {
    if (g_textWnd) { g_textCommit = nullptr; DestroyWindow(g_textWnd); }
}

void StartTextEntry(HWND owner, POINT sp, int fontSizePx, Gdiplus::ARGB,
                    std::function<void(const std::wstring&)> onCommit) {
    CancelTextEntry();
    int h = fontSizePx + 14, w = 220;
    g_textOwner = owner;
    g_textCommit = std::move(onCommit);
    g_textWnd = CreateWindowExW(WS_EX_TOOLWINDOW | WS_EX_TOPMOST,
        L"EDIT", L"", WS_POPUP | WS_BORDER | ES_AUTOHSCROLL | ES_WANTRETURN,
        sp.x, sp.y - h / 2, w, h,
        NULL, NULL, GetModuleHandleW(NULL), NULL);
    SendMessageW(g_textWnd, EM_LIMITTEXT, 500, 0);
    HFONT f = CreateFontW(-fontSizePx, 0, 0, 0, FW_NORMAL, 0, 0, 0,
                          DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Segoe UI");
    SendMessageW(g_textWnd, WM_SETFONT, (WPARAM)f, TRUE);
    ShowWindow(g_textWnd, SW_SHOWNOACTIVATE);
    SetFocus(g_textWnd);
    SetWindowSubclass(g_textWnd, TextProc, 1, 0);
}

// ================= 悬停提示（FR-3.14） =================
const wchar_t* TbName(int id) {
    switch (id) {
    case TB_OK: return L"确认 (Enter)";
    case TB_PIN: return L"贴图";
    case TB_SAVE: return L"另存为…";
    case TB_CANCEL: return L"取消 (Esc)";
    case TB_RECT: return L"矩形 (R)";
    case TB_ELLIPSE: return L"椭圆 (O)";
    case TB_LINE: return L"直线 (L)";
    case TB_ARROW: return L"箭头 (A)";
    case TB_PEN: return L"画笔 (B)";
    case TB_TEXT: return L"文字 (T)";
    case TB_MOSAIC: return L"马赛克 (M)";
    case TB_HIGHLIGHT: return L"高亮 (H)";
    case TB_UNDO: return L"撤销 (Ctrl+Z)";
    case TB_REDO: return L"重做 (Ctrl+Y)";
    case TB_C0: return L"颜色：红";
    case TB_C1: return L"颜色：黄";
    case TB_C2: return L"颜色：绿";
    case TB_C3: return L"颜色：蓝";
    case TB_C4: return L"颜色：白";
    case TB_C5: return L"颜色：黑";
    case TB_W0: return L"细线";
    case TB_W1: return L"中线";
    case TB_W2: return L"粗线";
    case TB_EDIT: return L"编辑";
    case TB_COPYIMG: return L"复制";
    case TB_ZOOMOUT: return L"缩小（也可滚轮）";
    case TB_ZOOMIN: return L"放大（也可滚轮）";
    case TB_OPAQUE: return L"透明度（Ctrl+滚轮）";
    case TB_CLOSE: return L"关闭（Esc / 双击）";
    default: return L"";
    }
}

void DrawTooltip(Gdiplus::Graphics& g, POINT pt, const RECT& clip,
                 const wchar_t* text, float scale) {
    if (!text || !*text) return;
    using namespace Gdiplus;
    FontFamily ff(L"Segoe UI");
    Font f(&ff, 12.0f * scale, FontStyleRegular, UnitPixel);
    RectF bb; StringFormat sf;
    g.MeasureString(text, -1, &f, PointF(0, 0), &sf, &bb);
    float pad = 5 * scale;
    float bw = bb.Width + pad * 2, bh = bb.Height + pad * 1.6f;
    float x = pt.x + 10 * scale;
    float y = pt.y - bh - 8 * scale;
    if (x + bw > clip.right - 4) x = pt.x - bw - 10 * scale;
    if (y < clip.top + 4) y = pt.y + 14 * scale;
    SolidBrush bg(Color(242, 15, 23, 42));
    g.FillRectangle(&bg, x, y, bw, bh);
    Pen bp(0xFF334155, 1.f);
    g.DrawRectangle(&bp, x, y, bw, bh);
    SolidBrush tb(0xFFF1F5F9);
    g.DrawString(text, -1, &f, PointF(x + pad, y + pad * 0.6f), &tb);
}
