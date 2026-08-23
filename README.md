# 钉图 TackShot

轻量级、绿色便携的 Windows 截图工具：热键截图 → 标注编辑 → **复制并贴图置顶**，贴图悬停可继续编辑。

- 单一可执行文件，解压即用，无需安装任何运行库
- 空闲驻留内存目标 ≤ 50MB（目标 30MB），默认完全离线、无遥测
- 技术栈：C++20 + Win32 + GDI+（Direct2D 为后续优化方向）
- 许可证：MIT（详见 LICENSE 与 THIRD-PARTY-NOTICES.txt）

## 构建

需要 MinGW-w64 GCC（x64）。仓库自带便携工具链下载脚本（放入 `tools/`）：

```bash
# 首次：将 w64devkit-x64-*.7z.exe 放入 tools/ 后
cd tools && ./w64devkit.7z.exe x -otools -y   # 解压出 tools/w64devkit/

./build.sh        # 产物：dist/TackShot.exe
```

或使用任意已安装的 MinGW-w64 / MSVC 直接编译 `src/*.cpp`。

## 使用

| 动作 | 默认热键 |
|------|----------|
| 区域截图 | Ctrl + Alt + A |
| 全屏截图 | Ctrl + Alt + F |
| 贴图（剪贴板图片钉到屏幕） | Ctrl + Alt + P |

截图确认（Enter / ✓）后：图片自动复制到剪贴板并钉在屏幕最前端；
鼠标悬停贴图浮现悬浮菜单（编辑 / 复制 / 保存 / 缩放 / 透明度 / 关闭）。

贴图操作：拖动=移动，滚轮=缩放，Ctrl+滚轮=透明度，双击或 Esc=关闭。

配置文件为 exe 同目录 `config.json`（绿色便携，删除目录即卸载）。

## 结构

```
src/
  common.h   公共声明与工具函数
  config.cpp 便携配置（flat JSON）与热键解析
  util.cpp   DIB/剪贴板/文件保存/日志
  editor.h/.cpp 标注模型、绘制与工具条
  capture.cpp 屏幕捕获 + 框选 + 编辑宿主
  pin.cpp    贴图窗口（置顶/缩放/透明度/悬浮菜单/就地编辑）
  app.cpp    入口：托盘/热键/单实例/自启动
需求文档.html  需求规格说明书（含第 13 章功能清单 RTM）
```

需求与状态跟踪见 `需求文档.html` 第 13 章（RTM）。
