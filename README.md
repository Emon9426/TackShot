# 钉图 TackShot

**轻量级截图 · 标注 · 贴图工具**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Windows%2010%2B-0078D4.svg)](#)
[![Offline](https://img.shields.io/badge/100%25-Offline-green.svg)](#隐私)

单一可执行文件，解压即用，无需安装任何运行库；空闲驻留内存约 12MB；完全离线，无广告、无遥测。

---

## 快速上手

| 动作 | 方式 |
|---|---|
| 区域截图 | `Ctrl + Alt + A` |
| 全屏截图 | `Ctrl + Alt + F` |
| 贴图（剪贴板图片钉到屏幕） | `Ctrl + Alt + P` |
| 确认 / 取消 | `Enter`（或工具条 ✓）/ `Esc` 或右键 |
| 托盘 | 双击 = 截图；右键 = 菜单（开机自启等） |

![工作流程](img/flow.svg)

**核心体验**：截图确认后，图片**自动复制到剪贴板**，同时**钉在屏幕最前端**（黑色边框标示）——去任意应用 `Ctrl+V` 直接粘贴，或在贴图上继续编辑。

## 功能详解

### 1. 截图

![框选与窗口吸附](img/capture.svg)

- **自由框选**：拖动鼠标框选。拖动过程中选区以**白色半透明高亮**显示，与黑色遮罩形成鲜明对比；实时显示坐标与尺寸；松开后可用 8 个控制点微调。
- **窗口吸附**：鼠标移到任意窗口上会自动高亮该窗口（不含阴影的精确边界），**单击即整窗截取**；按住拖动则随时转回自由框选。进入截图模式后光标全程为十字。
- 多显示器、100%–300% 高 DPI 缩放支持。

### 2. 标注编辑

![编辑工具条](img/editor.svg)

矩形 / 椭圆 / 直线 / 箭头 / 画笔 / 文字（支持输入法）/ 马赛克 / 高亮；6 色板 + 3 档线宽；`Ctrl+Z` / `Ctrl+Y` 撤销重做；工具快捷键 `R O L A B T M H`；悬停任意按钮会**放大提示**并显示功能名与快捷键。

### 3. 贴图

![贴图](img/pin.svg)

- 黑色边框明确标示"这是一张截图"；始终置顶；多张贴图共存
- **四角拖拽等比缩放，四边拖拽单轴拉伸**；滚轮缩放
- **透明度**：左键 ◐ 循环 0%→25%→75%→不透明；**右键 ◐** 呼出二级菜单（−/+ 步进、点击数字直接输入百分比）；`Ctrl+滚轮` ±10%；任何路径上限 95%，永不 100% 隐形
- 悬停浮现悬浮菜单（图片上方，不遮挡内容）：编辑 · 复制 · 保存 · 缩放 · 透明度 · 关闭
- **就地编辑**：贴图上直接补画标注，完成后自动同步回剪贴板（贴图上看到的 = 粘贴出去的）
- 拖动移动；双击 / `Esc` 关闭

### 4. 输出与配置

- 自动保存：PNG（默认）/ JPEG，时间戳命名，目录可配置；另存为对话框
- 便携配置 `config.json`（与 exe 同目录）：

```json
{
  "hotkey_region":  "Ctrl+Alt+A",
  "hotkey_full":    "Ctrl+Alt+F",
  "hotkey_pin":     "Ctrl+Alt+P",
  "confirm_action": "copy_pin",
  "format":         "png",
  "output_dir":     "",
  "jpeg_quality":   90
}
```

`confirm_action`：`copy_pin`（默认，复制+贴图）/ `copy`（仅复制）/ `copy_save`（复制+自动保存）

## 隐私

本软件**完全离线运行**：不联网、不上传、不收集任何数据。开机自启动默认关闭，仅在你从托盘菜单开启后写入注册表 HKCU Run。

## 下载与使用

- 直接下载本仓库的 **`release/TackShot` 文件夹**（或 [Releases](https://github.com/Emon9426/TackShot/releases) 中的 `TackShot-win64.zip`），解压后双击 `TackShot.exe` 即可——这个文件夹就是完整软件，删除即完全卸载。
- 交流与反馈：**emonzhang3438@outlook.com** —— 欢迎大家提出新的需求和 Bug！

## 常见问题：双击提示"Windows 无法访问指定设备、路径或文件"

本程序**不需要管理员权限**（exe 已内嵌应用程序清单，明确声明以当前用户身份运行；热键、托盘、剪贴板、图片库均为当前用户权限）。若首次运行被拦截，是 Windows 对"从网络下载的未签名程序"的安全策略所致，按顺序尝试：

1. 右键 `TackShot.exe` → **属性** → 勾选底部"**解除锁定**"（Unblock）→ 确定，再次双击；
2. 弹出蓝色 SmartScreen 警告时，点"**更多信息**"→"**仍要运行**"；
3. 杀毒软件报毒/隔离时，先用 `SHA256SUMS.txt` 核对文件哈希（PowerShell：`Get-FileHash TackShot.exe`），确认未被篡改后将整个文件夹加入信任区——本软件完全离线，绝无恶意行为，未签名引发的误报可向杀软厂商提交白名单申请；
4. 公司/学校电脑若上述全部无效，多为 AppLocker 或软件限制策略禁止运行用户目录下的任意 exe（与本软件无关），需管理员将其加入白名单。

## 构建

需要 MinGW-w64 GCC（x64）：

```bash
bash build.sh              # 产物 dist/TackShot.exe，并组装 release/ 发行文件夹与 zip
./dist/TackShot.exe /test  # 冒烟自测
```

技术栈：C++20 + Win32 + GDI+，零第三方依赖（MIT 许可证，见 [LICENSE](LICENSE) 与 [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt)）。

需求规格说明书（含功能跟踪表 RTM）见仓库根目录 `需求文档.html`。

## 联系作者

邮箱：**emonzhang3438@outlook.com** —— 欢迎大家提出新的需求和 Bug 报告，也欢迎功能讨论与代码贡献。

---

# English Documentation

# TackShot — Snap, Annotate, Pin

**A lightweight screenshot, annotation and pin tool for Windows.**

A single portable executable — no installer, no runtime dependencies, ~12 MB idle memory. Fully offline: no ads, no telemetry.

## Quick Start

| Action | How |
|---|---|
| Region capture | `Ctrl + Alt + A` |
| Fullscreen capture | `Ctrl + Alt + F` |
| Pin clipboard image | `Ctrl + Alt + P` |
| Confirm / Cancel | `Enter` (or ✓ button) / `Esc` or right-click |
| Tray | Double-click to capture; right-click for menu (auto-start etc.) |

![Workflow](img/flow.svg)

**Core experience**: after confirming a capture, the image is **automatically copied to the clipboard AND pinned on top of everything** (marked with a black border) — paste it anywhere with `Ctrl+V`, or keep editing it right on the pin.

## Features

### 1. Capture

![Capture](img/capture.svg)

- **Free region selection** — drag to select. While dragging, the region is highlighted with a translucent white veil contrasting against the dark mask; live size/position readout; 8 resize handles after release.
- **Window snap** — hover any window to highlight it (shadow-excluded precise bounds) and **click to capture the whole window**; press-and-drag to fall back to free selection. The cursor stays a crosshair throughout capture mode.
- Multi-monitor and 100%–300% high-DPI ready.

### 2. Annotate

![Editor](img/editor.svg)

Rectangle, ellipse, line, arrow, pen, text (IME support), mosaic, highlight; 6-color palette and 3 pen widths; `Ctrl+Z` / `Ctrl+Y` undo/redo; one-key tool switching (`R O L A B T M H`); hovering any button **enlarges it** and shows a tooltip with its name and hotkey.

### 3. Pin

![Pin](img/pin.svg)

- Black border clearly marks the screenshot; always on top; multiple pins coexist
- **Drag a corner to resize proportionally, drag an edge to stretch one axis**; wheel zoom
- **Opacity**: left-click ◐ cycles opaque → 25% → 75% → opaque; **right-click ◐** opens a sub-menu (−/+ steps, click the number to type an exact percent); `Ctrl+wheel` ±10%; capped at 95% everywhere — the pin never turns fully invisible
- Hover to reveal the floating menu (above the image, never covering it): edit, copy, save, zoom, opacity, close
- **Edit in place** — annotate directly on the pin; when finished, the result is automatically synced back to the clipboard (what you see on the pin is what gets pasted)
- Drag to move; double-click / `Esc` to close

### 4. Output & Configuration

- Auto-save as PNG (default) or JPEG with timestamped names; save-as dialog
- Portable `config.json` next to the exe:

```json
{
  "hotkey_region":  "Ctrl+Alt+A",
  "hotkey_full":    "Ctrl+Alt+F",
  "hotkey_pin":     "Ctrl+Alt+P",
  "confirm_action": "copy_pin",
  "format":         "png",
  "output_dir":     "",
  "jpeg_quality":   90
}
```

`confirm_action`: `copy_pin` (default: copy + pin) / `copy` (copy only) / `copy_save` (copy + auto-save).

## Privacy

TackShot is **fully offline**: no network, no uploads, no telemetry. Auto-start is opt-in only (HKCU Run, off by default).

## Download & Run

Download the **`release/TackShot` folder** from this repository (or `TackShot-win64.zip` from [Releases](https://github.com/Emon9426/TackShot/releases)), then run `TackShot.exe`. The folder is the whole app — delete it to uninstall completely.

## FAQ: "Windows cannot access the specified device, path, or file"

TackShot **does not require administrator rights** — the exe embeds an application manifest declaring `asInvoker`; hotkeys, tray, clipboard and the Pictures library all run at user level. If the first launch is blocked, it is Windows security policy for downloaded unsigned executables. Try in order:

1. Right-click `TackShot.exe` → **Properties** → tick **Unblock** at the bottom → OK, then run again;
2. On the blue SmartScreen warning click **More info** → **Run anyway**;
3. If your antivirus quarantines it, verify the hash first (`Get-FileHash TackShot.exe` vs `SHA256SUMS.txt`), then add the folder to your AV trust list — the app is fully offline and contains no malicious behavior; unsigned false positives can be reported to the vendor;
4. On corporate/school machines where none of the above works, AppLocker or Software Restriction Policies typically block *any* exe in user-writable folders (not specific to TackShot) — an administrator must whitelist it.

## Build

With any MinGW-w64 toolchain:

```bash
bash build.sh              # builds dist/TackShot.exe and assembles release/ + zip
./dist/TackShot.exe /test  # smoke test
```

Stack: C++20 + Win32 + GDI+, zero third-party dependencies (MIT License — see [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt)).

## Contact

Email: **emonzhang3438@outlook.com** — feature requests and bug reports are warmly welcome!

---

© 2026 钉图 TackShot 贡献者 · MIT License
