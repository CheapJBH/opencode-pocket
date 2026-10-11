# OpenCode Pocket（Android 本地聊天客户端）

这是一个原生 Android App 源码原型，目标是把 Windows 版 OpenCode 那种「以聊天为中心的编程 Agent 工作台」带到手机上。不是终端模拟器，也不是只会显示假回复的 UI Demo。

> **当前状态：Alpha 1 源码，尚未编译/真机验收。** 当前执行环境没有 Android SDK、Gradle，也没有接入实体 Android 手机，因此我不能诚实地声称 APK 已经编译成功或已在手机上跑通。代码已实现 OpenCode HTTP API 客户端与关键交互，但必须在 Android Studio/真机完成下面的验收清单，才可以当作可用版本。

## 这一版已经写了什么

- Kotlin + Jetpack Compose 深色聊天界面，手机单栏设计。
- 真实 OpenCode 会话列表、新建会话、消息历史、模型选择，以及停止当前任务。
- 使用官方 OpenCode Server API：检查健康状态、读取工作目录、读取 Provider/Model、创建会话、发送异步 prompt、轮询工具调用/回复、停止会话。
- 「连接/启动」先检查本机服务；未启动时，尝试通过 Termux `RUN_COMMAND` 授权接口执行 `opencode serve`。
- App 自己启动的服务会生成随机密码，设置 `OPENCODE_SERVER_PASSWORD` 并通过 HTTP Basic Auth 访问。连接到手动启动的无密码服务时会显示安全提醒。
- Agent 工作期间启动 Android 前台服务、常驻通知和有时限的部分 Wake Lock；启动 OpenCode 时也会调用 Termux 的 `termux-wake-lock`。
- 工作目录可配置。改路径只影响下一次启动的服务；已运行服务不会自动切换工作目录。
- 有本地 API 冒烟测试脚本和真机验收清单。

## 为什么第一版先用 Termux 承载 OpenCode

```text
OpenCode Pocket（原生聊天 UI）
  ├─ OpenCode HTTP API：127.0.0.1:4096
  ├─ 后台任务通知 / Wake Lock
  └─ Termux RUN_COMMAND 桥接
              │
              ▼
Termux 中的 Android/Bionic 版 OpenCode（opencode serve）
              │
              ▼
已配置的模型 Provider、Agent 工具、Git 与项目文件
```

Android/Bionic 版 OpenCode 的原生运行时并不是把桌面二进制复制进 APK 就能实现；它涉及 Android 的 Bionic ABI、Bun/原生终端依赖等问题。第一阶段先复用已有的 Termux 运行环境，把「真实聊天 → 本地 Agent → 工具执行 → 后台任务」打通。后续可以再考虑把运行时整合进独立 APK，但那是另一项需要单独验证和维护的工程。

Android/Termux OpenCode 参考实现：<https://github.com/Hope2333/opencode-termux>
OpenCode Server API 官方文档：<https://opencode.ai/docs/server>

## 需要什么

- Android 9+，建议 ARM64 手机。
- Termux（来自可信分发源）。
- 按 Android/Bionic 适配的 OpenCode，且 `opencode --version` 能正常运行。
- OpenCode 内至少已配置一个模型 Provider 和凭据。App 默认使用 OpenCode 默认模型，也可以选择服务端报告的模型。

## 第一次设置 Termux

### 1. 安装并验证 OpenCode

按当前 Android/Bionic 版 OpenCode 项目的最新说明安装。先在 Termux 里确认：

```sh
opencode --version
```

请先在 Termux 中配置好你实际要用的模型 Provider。若 Provider / 凭据 / 网络本身不可用，App 不会凭空生成真实回答。

### 2. 允许外部 App 执行 Termux 命令

**先理解这一设置的安全影响：**它允许用户明确授权过的第三方 App 在 Termux 环境里执行命令。只有你信任此 App 时才启用。

```sh
mkdir -p ~/.termux
touch ~/.termux/termux.properties
grep -q '^allow-external-apps' ~/.termux/termux.properties \
  && sed -i 's/^allow-external-apps.*/allow-external-apps = true/' ~/.termux/termux.properties \
  || printf '\nallow-external-apps = true\n' >> ~/.termux/termux.properties
termux-reload-settings
```

按提示完全关闭并重新打开 Termux，使设置生效。

### 3. 准备项目目录

项目目录必须存在，并且 Termux 对它有访问权限。例如：

```sh
mkdir -p ~/projects/demo
cd ~/projects/demo
git init
```

### 4. 授权 App 使用 Termux

安装 APK 后，打开 Android 系统设置：

**应用 → OpenCode Pocket → 权限 / 其他权限 →「在 Termux 环境中运行命令」**。

不同 ROM 的文字可能略有不同。若没有授予此权限，App 不能通过 Termux 自动启动 OpenCode。

### 5. 启动本地服务

打开 OpenCode Pocket 的设置，将工作目录设为例如 `~/projects/demo`，保存后点右上角连接/启动图标。

App 会通过 Termux 的 `env` 启动服务，并设置随机 `OPENCODE_SERVER_PASSWORD`：

```sh
env OPENCODE_SERVER_USERNAME=opencode OPENCODE_SERVER_PASSWORD='<每次安装生成的随机密码>' opencode serve --hostname 127.0.0.1 --port 4096
```

上面仅示意实际启动结构；密码由 App 在其私有设置中生成/保存，并自动用于 API Basic Auth。服务只绑定本机回环地址 `127.0.0.1`，不是局域网服务，但“只绑定 localhost”不代表与同一台手机上的其他 App 完全隔离。App 会轮询健康检查约 18 秒；若仍无法连接，会显示错误提示。请打开 Termux 查看服务实际状态，而不是把「启动请求已发送」误认为「已启动成功」。

如果先前已经在 Termux 手动启动了无密码 Server，App 仍允许连接，但会显示安全提醒；建议先在 Termux 结束该进程，再由 App 启动带随机 API 密码的服务。

## 先分开验证服务，再验证 App

可先在 Termux 的项目目录里手动运行（这是**无 API 密码保护的诊断方式**，只用于临时排查；确认后停止进程，再由 App 启动受密码保护的服务）：

```sh
opencode serve --hostname 127.0.0.1 --port 4096
```

另开一个 Termux 会话执行：

```sh
curl -fsS http://127.0.0.1:4096/global/health
curl -fsS http://127.0.0.1:4096/path
curl -fsS http://127.0.0.1:4096/config/providers
curl -fsS http://127.0.0.1:4096/session
```

也可以运行本项目的脚本：`scripts/termux/check-opencode-server.sh`。

如果这些 API 本身不通，先解决 OpenCode/Termux 运行问题，再排查 App。这样不会把底层服务故障误判成聊天 UI 故障。

## 后台运行设置

任务开始后，App 会使用前台服务显示常驻通知，并持有有时限的部分 Wake Lock；启动服务时也会向 Termux 请求 `termux-wake-lock`。设置页可以打开本 App 的忽略电池优化入口，并打开 Termux 的系统设置。

务必分别检查 **OpenCode Pocket 和 Termux** 的电池优化及后台活动限制。Termux 才是真正持有 `opencode serve` 进程的应用，仅豁免聊天 UI 并不够。

后台执行不是「永不被杀」的保证：强制停止、重启、内存压力和部分厂商的后台策略仍可能中断进程。本项目在任务状态不明时会显示错误，而不会假装任务还在跑。

测试完后可以在 Termux 手动释放锁：

```sh
termux-wake-unlock
```

## 构建 APK

推荐使用 Android Studio 打开此目录，安装 Android SDK Platform 35、Build Tools 35.0.0 和 JDK 17。项目使用 Android Gradle Plugin 8.10.1、Gradle 8.11.1 与 Kotlin 2.1.20。

此源码压缩包暂未包含 Gradle Wrapper JAR；在已有 Gradle 的环境中可先执行 `gradle wrapper`，再进行构建。项目内的 GitHub Actions 工作流也会在推送代码后执行 `assembleDebug` 并上传 Debug APK artifact。

**我没有在当前环境生成 APK**，因为这里没有 Android SDK/Gradle，也没有真机供安装验证。不要把源码 ZIP 当作可直接安装的 APK。

## 当前功能边界

- 运行时还没有内置在 APK；Alpha 1 使用 Termux。
- 若用户自行启动一个无密码 Server，不能由 App 在不中断进程的情况下追加入密码；App 会提示安全风险。
- 进度通过轮询同步，而非 OpenCode SSE，工具状态显示可能有延迟。
- 尚未实现完整 Markdown/代码高亮、文件树、行内 Diff、可视化文件选择、终端/TUI 切换、事件流式渲染，以及完整的进程重启恢复体验。
- 目前未在真机验证服务启动权限、Android 各版本前台服务行为、电池管理和锁屏长任务表现。

## 使用的官方 API

- `GET /global/health`
- `GET /path`
- `GET /config/providers`
- `GET /session`
- `POST /session`
- `GET /session/:id/message`
- `POST /session/:id/prompt_async`
- `GET /session/status`
- `POST /session/:id/abort`
