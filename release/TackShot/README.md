# 钉图 TackShot

**轻量级截图 · 标注 · 贴图工具（Java 版 V2.0）**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Windows%2010%2B-0078D4.svg)](#)
[![Offline](https://img.shields.io/badge/100%25-Offline-green.svg)](#隐私)

绿色免安装：一个文件夹（`TackShot.jar` + `lib\` + `start.bat`）就是完整软件，**不含任何 exe**，适合禁止运行 exe 的办公环境；完全离线，无广告、无遥测。

> V2.0 起实现平台由 C++ 切换为 **Java 11+（Swing/AWT + JNA）**，运行需安装 JDK/JRE 11 或 17。功能与交互和 V1.x 完全一致。

---

## 环境要求与快速上手

1. 安装 **JDK 11 或 17**（任一即可，[Adoptium Temurin](https://adoptium.net/) 免费开源）；
2. 下载本仓库 **`release/TackShot` 文件夹**（整个文件夹），双击 **`start.bat`** 启动；
3. 托盘出现图钉图标即常驻成功。

| 动作 | 方式 |
|---|---|
| 区域截图 | `Ctrl + Alt + A` |
| 全屏截图 | `Ctrl + Alt + F` |
| 贴图（剪贴板图片钉到屏幕） | `Ctrl + Alt + P` |
| 确认 / 取消 | `Enter`（或工具条 ✓）/ `Esc` 或右键 |
| 托盘 | 双击 = 截图；右键 = 菜单（开机自启等） |

![工作流程](img/flow.svg)

**核心体验**：截图确认后，图片**自动复制到剪贴板**，同时**钉在屏幕最前端**（黑色边框标示）——去任意应用 `Ctrl+V` 直接粘贴，或在贴图上继续编辑。

**V2.0 交付语义**（按用户需求定制）：

- 截图编辑器点 **保存**：自动保存到 `图片\TackShot` + 复制剪贴板 + 结束截图；
- 贴图悬浮菜单 **复制**：复制剪贴板 + 关闭该贴图；
- 贴图悬浮菜单 **保存**：自动保存 + 复制剪贴板 + 关闭该贴图。

## 功能详解

### 1. 截图

![框选与窗口吸附](img/capture.svg)

- **自由框选**：拖动框选，拖动中选区白色半透明高亮，实时显示坐标尺寸；松开后 8 控制点微调。
- **窗口吸附**：悬停任意窗口自动高亮（无阴影精确边界），**单击整窗截取**；拖动随时转回自由框选。全程十字光标。
- 多显示器、100%–300% 高 DPI 缩放支持。

### 2. 标注编辑

![编辑工具条](img/editor.svg)

矩形 / 椭圆 / 直线 / 箭头 / 画笔 / 文字（支持输入法）/ 马赛克 / 高亮；6 色板 + 3 档线宽；`Ctrl+Z` / `Ctrl+Y` 撤销重做；工具快捷键 `R O L A B T M H`；悬停按钮**放大提示**并显示功能名与快捷键。

### 3. 贴图

![贴图](img/pin.svg)

- 黑色边框标示截图；始终置顶；多张贴图共存；**窄贴图窗口自动加宽，悬浮菜单永远完整显示**
- **四角拖拽等比缩放，四边拖拽单轴拉伸**；滚轮缩放
- **透明度**：左键 ◐ 循环 0%→25%→75%→不透明；右键 ◐ 二级菜单（−/+ 步进、点击数字输入百分比）；`Ctrl+滚轮` ±10%；上限 95% 永不隐形
- 悬停浮现悬浮菜单（图片上方）：编辑 · 复制 · 保存 · 缩放 · 透明度 · 关闭
- **就地编辑**：贴图上直接补画标注，完成后自动同步回剪贴板
- 拖动移动；双击 / `Esc` / 右键关闭

### 4. 输出与配置

- 自动保存 PNG（默认）/ JPEG，时间戳命名；目录可配置
- 便携配置 `config.json`（与 jar 同目录）：

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

## 常见问题

**Q：双击 start.bat 没有反应？**
bat 会检测 `javaw`：请确认已安装 JDK/JRE 11/17 且在 PATH 中（或设置了 `JAVA_HOME`）。仍不行时，把 bat 里的 `javaw` 改成完整路径，例如 `"C:\Program Files\Java\jdk-17\bin\javaw.exe"`。

**Q：公司电脑禁止运行 exe？**
本发行包**不含任何 exe**（jar + bat），JRE 由公司 IT 统一安装的白名单 `java.exe/javaw.exe` 承载运行，无 SmartScreen/杀软 exe 拦截问题。

**Q：内存占用？**
Java 版常驻约 60–120MB（启动参数已限 `-Xmx128m`）。相比 C++ 版（约 12MB）是运行时代价的交换——换来的是免 exe 的合规运行环境。

## 构建

需要 JDK 11+（11/17 均可）：

```bash
bash build.sh                     # 产物 dist/TackShot.jar，并组装 release/ 发行文件夹与 zip
java -jar dist/TackShot.jar --test  # 冒烟自测（3 项）
```

技术栈：Java 11 + Swing/AWT + JNA 5.14（Apache-2.0，见 [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt)；许可证 MIT，见 [LICENSE](LICENSE)）。

需求规格说明书（含功能跟踪表 RTM）见仓库根目录 `需求文档.html`。

## 联系作者

邮箱：**emonzhang3438@outlook.com** —— 欢迎大家提出新的需求和 Bug 报告，也欢迎功能讨论与代码贡献。

---

# English Documentation

# TackShot — Snap, Annotate, Pin (Java V2.0)

**A lightweight screenshot, annotation and pin tool for Windows — now in Java.**

A single portable folder (`TackShot.jar` + `lib\` + `start.bat`) — **no exe at all**, ideal for locked-down corporate machines. Fully offline: no ads, no telemetry.

> Since V2.0 the implementation is **Java 11+ (Swing/AWT + JNA)**; install JDK/JRE 11 or 17 to run. Features and interactions match V1.x exactly.

## Quick Start

1. Install **JDK 11 or 17** (e.g. [Adoptium Temurin](https://adoptium.net/));
2. Download the **`release/TackShot` folder** from this repo and double-click **`start.bat`**;
3. The pin icon in the tray means it's running.

| Action | How |
|---|---|
| Region capture | `Ctrl + Alt + A` |
| Fullscreen capture | `Ctrl + Alt + F` |
| Pin clipboard image | `Ctrl + Alt + P` |
| Confirm / Cancel | `Enter` (or ✓) / `Esc` or right-click |
| Tray | Double-click to capture; right-click for menu |

**V2.0 delivery semantics** (per user request): the editor **Save** button auto-saves to `Pictures\TackShot` + copies + closes the capture; the pin menu **Copy**/**Save** copy (and save) then close the pin.

## Features

- **Capture**: free-region selection with white veil + live readout + 8 handles; window snap (shadow-excluded bounds, click to capture); multi-monitor, 100%–300% DPI.
- **Annotate**: rect/ellipse/line/arrow/pen/text(IME)/mosaic(3 styles + wheel granularity)/highlight; 6 colors × 3 widths; undo/redo; `R O L A B T M H` hotkeys; hover-enlarge tooltips.
- **Pin**: always-on-top, black border; corner = proportional resize, edge = single axis; wheel zoom; opacity ◐ (cycle / sub-menu / Ctrl+wheel, 95% cap); hover menu that always fits (narrow pins auto-widen); edit in place with clipboard sync; drag / double-click / Esc to close.
- **Output**: auto-save PNG/JPEG with timestamped names; portable `config.json`.

## Privacy

Fully offline. Auto-start is opt-in (HKCU Run, off by default).

## FAQ

- **start.bat does nothing** — ensure JDK/JRE 11/17 is installed and `javaw` is on PATH (or `JAVA_HOME` set); or edit the bat to point at `javaw.exe` directly.
- **exe blocked by policy** — this package ships no exe; the whitelisted corporate `javaw.exe` hosts the app.
- **Memory** — ~60–120MB idle (`-Xmx128m` preset); the trade for an exe-free environment.

## Build

```bash
bash build.sh                          # builds dist/TackShot.jar and assembles release/ + zip
java -jar dist/TackShot.jar --test     # smoke test (3 checks)
```

Stack: Java 11 + Swing/AWT + JNA 5.14 (Apache-2.0 — see [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt); MIT License — see [LICENSE](LICENSE)).

## Contact

Email: **emonzhang3438@outlook.com** — feature requests and bug reports are warmly welcome!

---

© 2026 钉图 TackShot 贡献者 · MIT License
