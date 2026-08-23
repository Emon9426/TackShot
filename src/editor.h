// editor.h — 标注对象模型 + 绘制 + 工具条
#pragma once
#include "common.h"
#include <functional>

enum class Tool { None, Rect, Ellipse, Line, Arrow, Pen, Text, Mosaic, Highlight };

struct Shape {
    Tool        tool = Tool::Rect;
    Gdiplus::ARGB color = 0xFFEF4444;
    float       penW = 4.f;
    POINT       a{}, b{};              // 画布坐标系（选区/贴图本地坐标）
    std::vector<POINT> pts;            // 画笔轨迹
    std::wstring text;
    int         fontSize = 26;
    int         mStyle = 0;            // 马赛克样式：0 方格 1 模糊 2 纯黑
    int         mSize = 20;            // 马赛克粒度（px）
};

struct Editor {
    std::vector<Shape> shapes, redo;
    Tool         cur = Tool::None;
    Gdiplus::Color color{ 0xFF, 0xEF, 0x44, 0x44 };
    int          widthIdx = 1;         // 0/1/2 → 细/中/粗
    int          mosaicStyle = 0;      // 0 方格 1 模糊 2 纯黑
    int          mosaicSize = 20;      // 粒度 6..80，滚轮调节
    Shape        draft;
    bool         drafting = false;

    void Push(const Shape& s) { shapes.push_back(s); redo.clear(); }
    bool Undo() { if (shapes.empty()) return false; redo.push_back(shapes.back()); shapes.pop_back(); return true; }
    bool Redo() { if (redo.empty()) return false; shapes.push_back(redo.back()); redo.pop_back(); return true; }
};

int  PenWidth(int idx);                                  // 2/4/7
int  FontSizeFor(int idx);                               // 18/26/36
void DrawShape(Gdiplus::Graphics& g, const Shape& s,
               Gdiplus::Bitmap* base, POINT baseOff);
void DrawShapes(Gdiplus::Graphics& g, const std::vector<Shape>& v,
                Gdiplus::Bitmap* base, POINT baseOff);
bool ToolFromKey(UINT vk, Tool& t);                      // 工具快捷键 R/O/L/A/B/T/M/H

// ---------------- 工具条 ----------------
enum TbId {
    TB_NONE = 0,
    TB_OK, TB_PIN, TB_SAVE, TB_CANCEL,
    TB_RECT, TB_ELLIPSE, TB_LINE, TB_ARROW, TB_PEN, TB_TEXT, TB_MOSAIC, TB_HIGHLIGHT,
    TB_UNDO, TB_REDO,
    TB_C0, TB_C1, TB_C2, TB_C3, TB_C4, TB_C5,
    TB_W0, TB_W1, TB_W2,
    TB_EDIT, TB_COPYIMG, TB_ZOOMOUT, TB_ZOOMIN, TB_OPAQUE, TB_CLOSE,
    TB_MS_MOSAIC, TB_MS_BLUR, TB_MS_BLACK   // 马赛克样式二级菜单
};

struct TbBtn { int id; RECT r; };

enum class TbMode { Editor, PinHover, PinEdit };

struct Toolbar {
    std::vector<TbBtn> btns;
    RECT bar{};
    int  zoomPct = 100;      // PinHover 模式显示的百分比
    float scale = 1.0f;      // DPI 缩放（96 基准），Layout 时设置

    void Layout(const RECT& host, const RECT& scr, TbMode mode, float dpiScale = 1.0f);
    void Draw(HDC dc, const Editor* ed, int hover, TbMode mode,
              float hoverScale = 1.0f) const;   // hoverScale：悬停按钮放大系数（动画）
    int  Hit(int x, int y) const;
};

// 悬停提示（FR-3.14）：按钮功能名 + 快捷键；绘制提示条
const wchar_t* TbName(int id);
void DrawTooltip(Gdiplus::Graphics& g, POINT pt, const RECT& clip,
                 const wchar_t* text, float scale);

// ---------------- 文字输入弹窗（顶层 EDIT，天然支持 IME） ----------------
void StartTextEntry(HWND owner, POINT screenPos, int fontSizePx, Gdiplus::ARGB color,
                    std::function<void(const std::wstring&)> onCommit);
void CancelTextEntry();
bool TextEntryActive();

// ---------------- 马赛克样式二级菜单 ----------------
struct MosaicFlyout {
    RECT bar{};
    bool visible = false;

    void Layout(const RECT& anchorBtn, const RECT& clip, float scale);
    void Draw(HDC dc, const Editor& ed, float scale) const;
    int  Hit(int x, int y) const;          // 命中返回 TB_MS_*，否则 0
    void Hide() { visible = false; }
};

// 粒度 HUD：光标旁短暂显示当前样式与粒度（滚轮调节时）
void DrawSizeHud(Gdiplus::Graphics& g, POINT pt, const Editor& ed, float scale);
// 马赛克按钮右下角 ▾ 区域（用于展开二级菜单）
RECT MosaicCaretZone(const Toolbar& tb);
