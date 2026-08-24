# 钉图 TackShot · AI 版（V2.3-AI · 独立目录）

本目录是 TackShot 的 **AI 独立版本**，与仓库根目录的**无 AI 主版本完全隔离、互不影响**：
源码独立（`ai/src-java`）、构建产物独立（`ai/dist`、`ai/release`）、
运行身份独立（版本串 / 单实例锁 `tackshot-ai.lock` / 配置 `config-ai.json` / 日志 `tackshot-ai.log` /
自启注册名 `TackShotAI` 全部独立）。两版本可**同时运行**（主版本占用热键时，AI 版热键注册失败并气泡提示，其余功能不受影响）。

## 功能（FR-8.1 ~ 8.4 · P0）

- **贴图右键 → AI 菜单**：提取文字（OCR） / 翻译成中文 / 翻译成 English / AI 设置…（原「右键=关闭贴图」保留为菜单末项）
- **AI 结果浮窗**：停靠贴图旁（放不下自动换侧/夹紧），继承贴图置顶层级；流式逐字渲染；
  翻译为「原/译」对照着色；**复制为显式按钮**（遵守 V2.0.3 剪贴板规则）；重试 / 换模型（快速 gpt-4o-mini · 均衡 gpt-4o · 深度 claude-sonnet-4）；Esc 关闭即取消请求
- **设置三标签页**（热键与目录 / ✨ AI / 关于）：AI 页含启用开关、PAT 录入（密码框）/测试连接/显示/清除、模型三档、翻译目标语言、发送前确认（默认开）
- **托盘菜单**：「✨ AI 识别剪贴板图片」
- **编辑器工具条 AI 按钮**（截图编辑器与贴图编辑态）：对当前选区/贴图（含标注合成图）执行提取文字

## 技术路线（D4 · 用户决策）

官方 **GitHub Copilot SDK**：`com.github:copilot-sdk-java` 1.0.11（Maven Central），
客户端懒启动（首次调用拉起 Copilot CLI 子进程，组件目录 `copilot-home/`），
**空闲 3 分钟自动停止释放 CLI 子进程**。认证＝设置页粘贴 GitHub 细粒度 PAT（`github_pat_`），
仅存 **Windows 凭据管理器**（DPAPI，条目 `TackShotAI/GitHubPAT`），不落任何文件。

## 构建与运行

```bash
# 构建（需 JDK 17+；本机用 JDK 18：JAVA_HOME 指向之）
JAVA_HOME='/c/Program Files/Java/jdk-18.0.2.1' bash ai/build.sh
# 产物：ai/dist/TackShotAI.jar（本地）与 ai/release/TackShotAI/（发行包，双击 start.bat）

# 冒烟（3/3 断言，与主版本同口径）
cd ai/dist && MSYS2_ARG_CONV_EXCL='*' '/c/Program Files/Java/jdk-18.0.2.1/bin/java' -jar TackShotAI.jar /test

# AI 界面视觉自检（贴图 + 未配置引导卡 + 设置 AI 页）
cd ai/dist && MSYS2_ARG_CONV_EXCL='*' '/c/Program Files/Java/jdk-18.0.2.1/bin/java' -jar TackShotAI.jar /aitest

# 升级 SDK 版本：改 ai/pom.xml 后
mvn -f ai/pom.xml dependency:copy-dependencies -DoutputDirectory=ai/lib   （并同步 build.sh 中 jar 文件名）
```

运行要求：JDK/JRE **17+**（Copilot SDK 要求）；首次 AI 调用需联网（CLI 首次下载组件）；需 GitHub Copilot 订阅。

## 使用步骤

0. **前置条件（一次性，缺一不可）**：
   - 安装 GitHub Copilot CLI：`winget install GitHub.Copilot` 或 `npm install -g @github/copilot`（需 Node 22+）。
     **SDK 不会自动下载/捆绑 CLI**（文档表述与 Java 版实测不符，2026-08-24 复现：未装 CLI 时 start() 0.3 秒即"管道正在被关闭"失败）；装好后冷启动约 1.6 秒。
   - GitHub Copilot 订阅（Free/Pro/Business/Enterprise 均可）
1. 托盘 → 设置… → ✨ AI → 勾选「启用 AI 功能」
2. 粘贴 GitHub 细粒度 PAT（github.com → Settings → Developer settings → Personal access tokens → Fine-grained）→「测试连接」应显示可用视觉模型数
3. 对贴图右键 →「提取文字」；首次会弹「发送前确认」（可选「不再询问」）

若测试连接失败：提示"未检测到 GitHub Copilot CLI"＝第 0 步未做；"令牌未通过认证"＝检查 PAT 有效性与订阅，或先在终端运行一次 `copilot` 按提示登录。

## 关于安全软件

TackShotAI.jar 与 start.bat 本身**不执行 PowerShell**。SDK 拉起 CLI 子进程为直接进程创建（实测本机 Defender 未拦截）。
开发/验证时若用 `powershell Start-Process -WindowStyle Hidden` 启动会被部分杀软判定为"隐藏执行 PowerShell"而拦截——
请直接运行 `start.bat` 或 `javaw -jar TackShotAI.jar`。

## 已验证 / 待验证

已验证（2026-08-24，本机）：
- 构建（javac --release 17）通过；`/test` 冒烟 3/3（与主版本同时运行，配置/日志/热键互不干扰）
- `/aitest` 视觉验证：设置三标签页（AI 页控件齐全）、AI 结果浮窗未配置引导卡（420×360 深色无乱码）、贴图右键 AI 菜单（六项完整无乱码）

待验证（需真实 PAT 与外网，PoC 清单）：
- 真实识别链路：BlobAttachment 图片附件格式（base64）是否被 SDK/CLI 正确接受；流式 delta 事件回传
- CLI 子进程实际内存/下载体积（性能评估的 PoC 项）
- 公司机 JDK 17 + 网络（api.githubcopilot.com / GitHub CLI 下载域名）可达性
- 订阅档位下模型 ID 实际可用性（gpt-4o-mini/gpt-4o/claude-sonnet-4 为默认档，`listModels` 可实测校正）

## 与主版本的代码关系

`ai/src-java` 是主版本源码的完整副本 + AI 增量：
新增包 `tackshot.ai`（AiService 门面 / CopilotSdk 适配层 / TokenVault 凭据管理器 / ImagePrep / PromptLib / AiResultWindow / AiMenu）；
主版本类仅做挂载与可见性微调（Pin 右键与访问器、Tb.TB_AI 按钮、Settings 三标签页、Main 托盘项与 /aitest、
Cfg ai_* 字段、Log/Cfg/Settings 公开化）。**主版本目录（src-java/、build.sh、start.bat、dist/、release/）保持零改动**——
后续主版本功能更新需手动同步到本副本（或反之），建议在 RTM 变更记录中注明双版本同步事项。

许可证：主程序 MIT；SDK 及依赖（copilot-sdk-java、Jackson、JNA）见 THIRD-PARTY-NOTICES.txt。
