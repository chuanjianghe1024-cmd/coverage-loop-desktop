# Tauri 桌面试用版 · 1.4.0-beta.1

第一阶段用 Tauri 2 / Rust 替换 Electron 桌面壳，继续使用 React、Aceternity UI、浅绿色主题和 Java 1.3 执行内核。Maven Reactor、精确测试范围、覆盖率门禁、超时重试、当前轮结束后停止及立即停止的语义沿用现有内核。

## 安装与数据

- 安装包名为 `Coverage Loop Tauri`，应用标识 `com.coverageloop.desktop.tauri`，与正式版并行安装。
- 保留内置 Java 运行时，启动软件无需另外安装 JDK；执行目标 Maven 工程仍使用工程配置的 JDK / Maven。
- 使用已有 WebView2；电脑缺少时，安装器联网下载。没有把离线 WebView2 安装器放入应用包。内网机器应先部署 WebView2。
- 试用版使用独立 SQLite 工作区。第一次启动时，检测 Electron 的 `Coverage Loop`、`@coverage-loop/desktop` 或 `coverage-loop-desktop` 数据目录，选取最近更新的旧数据库。
- 导入使用 SQLite `VACUUM INTO`，包含已提交 WAL 数据；不直接复制正在使用的 `.db` 文件，不修改旧数据库，不覆盖已经存在的试用版数据库。
- 历史记录中的日志继续指向原工程 `.coverage-loop`。导入记录可从试用版删除，文件清理被禁用，以保留原版使用的日志。新的试用任务仍支持原来的可选日志清理。
- 两个版本针对同一工程时，请先结束一个版本中的任务，再在另一个版本中运行；它们仍操作同一份工程源码。

## 开发与构建

Windows 构建环境：Node.js 22.12+、完整 JDK 17（含 `jlink`）、Rust stable，以及 Visual Studio Build Tools 的“使用 C++ 的桌面开发”和 Windows SDK。Rust/C++ 工具仅用于构建，不需要用户安装。

```bash
npm ci
npm run dev:tauri
npm run package:tauri
```

产物位于 `apps/desktop/src-tauri/target/release/bundle/nsis/`。原来的 `npm run dev`、`npm run package:win` 继续构建 Electron 版本。

Linux 原生开发需要 Tauri 官方列出的 GTK / WebKitGTK 4.1 等依赖。无桌面依赖时可验证桥接核心：

```bash
cargo test --manifest-path apps/desktop/src-tauri/Cargo.toml --lib --no-default-features
```

设置 `COVERAGE_TEST_ENGINE_JAR` 为已构建 Java JAR 的绝对路径，可同时运行真实 Java 启动、鉴权和退出测试。默认不开启这个耗时集成用例。

## 桌面实现

- `src-tauri/src/engine.rs`：持有 Java 子进程、标准输入生命线、随机令牌、本机 HTTP 客户端、工程白名单和恢复终端状态。请求绕过系统代理。
- `process.rs`：关闭时以 stdin EOF 通知 Java 停止工作并关闭数据库；Windows Job Object 在壳异常退出时清理其进程树。
- `policy.rs`：仅允许已知操作、当前工程证据路径及本地页面；文件路径经过真实路径校验。
- `recovery.rs`：PowerShell 使用 UTF-16LE 编码命令与字面量参数，兼容中文、空格和引号；恢复终端与新任务/日志删除互斥。
- `main.rs`：文件选择、日志打开、自定义标题栏、关闭确认、内核异常提示。端口与令牌由 Rust 持有，前端没有任意 shell / HTTP / 文件访问权限。
- `src/lib/tauri.ts`：适配现有 Bridge 接口，Electron 与 Tauri 共用业务页面和测试。

## 验证与体积比较

CI 分别执行前端、Java 和 Rust 验证，并构建两个 Windows 安装包。Tauri 产物还会在 Windows runner 上静默安装，启动实际安装的程序、WebView 页面和内置 Java，验证页面能够通过原生桥接通信、扫描工程、保存配置、正常退出且不遗留内核进程。

`package-comparison` 使用本次同一提交的两个 `.exe` 实测大小，生成 `package-sizes.json` 和 `package-sizes.md`。比较不把下载的 WebView2 算进 Tauri 安装包；开发依赖目录和安装后展开大小不参与这个比较。

文件选择对话框、不同 DPI 的窗口拖拽/缩放、真实助手恢复终端及企业网络环境，仍应在实际使用的 Windows 机器完成试用验收。

## 后续 Rust 内核迁移

在这版实测体积与运行反馈基础上，再迁移循环状态机、Maven/Agent 进程管理、报告解析和 SQLite。保留少量 Java Maven 扩展，由目标工程 JDK 加载；完成后才考虑移除应用自带的 Java 运行时。
