# Coverage Loop

Java + React 的本地单元测试补充工作台。选择工程，定义模块和包/类范围，配置 Agent，以真实 Maven / Surefire / JaCoCo 结果驱动逐轮补测。

**技术栈：Java 17 · React · Electron · Aceternity UI · SQLite**

## 使用流程

1. 选择项目根目录和 Maven `settings.xml`（可选），确认首页的本地仓库短路径（Windows 默认 `D:/m2`），扫描模块。
2. 直接勾选模块 → 包 → 类树，取消勾选即排除；无需手写类或测试匹配规则。选择行覆盖率目标。
3. 编辑补测与失败修复提示词，配置 Hermes / OpenCode、模型、每批类数、单轮时限和重试等待；默认持续运行直到达标。
4. 保存配置后运行基线或自动补测，在看板中查看每轮 DT 总数、执行 DT 变化、失败测试、未达标类、覆盖率和测试文件改动。
5. 在运行记录中回看历史任务；SQLite 自动保存项目、配置与每轮结果。

## 覆盖率统计

侧栏“覆盖率统计”提供独立测量，不需要 Agent。可选择多个模块、包和类，为不同范围新建、保存、另存为和删除配置。配置写入 SQLite，同时保存到目标工程 `.coverage-loop/statistics/configs/<id>.json`。

结果按工程 → 模块 → 包 → 类展示，每层显示 **已覆盖行 / 可执行总行 · xx.xx%**，按行数加权汇总。缺失报告显示“未测”，无可执行行显示“—”。每次运行另存历史与 `statistics-tree.json`，编辑配置不会修改历史结果。

测量只包含勾选的生产类。执行测试时，按所选生产类所在包筛选测试源文件；选择整个模块则运行其测试文件。生产类和测试无法静态一一对应，因此选择一个类时会执行同包测试。预构建可执行 `install -DskipTests=true`。正式测试始终从根 POM 用 `-pl <所选模块> -am` 构建一次 reactor，依赖模块参与构建但跳过测试；随软件提供的 Maven 扩展为每个选中模块独立应用测试清单，不修改工程 POM。测试清单写入文件，避免把大量类名拼接进 Windows 命令行；补测循环也采用相同范围规则。

## ProGuard 与 Windows 界面

本地 Maven 仓库已移到工程首页，默认 `D:/m2`，旁边有 ProGuard 提示。实际执行添加 `-Dmaven.repo.local=...`，覆盖 settings 中的本地仓库路径，私服、镜像和认证仍沿用 settings。清空此字段即恢复 settings 默认仓库。短路径减少 ProGuard 依赖参数长度；若依赖极多仍触发 206，需要对应 ProGuard 插件支持参数文件，这里不修改目标工程 POM。

界面采用浅绿色主题，使用 Aceternity Bento Grid / Hover Border Gradient，以及页面切换、树展开、按钮悬停、数字和进度条动效。系统启用“减少动态效果”时相应减弱。标题栏使用相同绿色，保留 Windows 原生最小化、最大化与关闭按钮。应用与安装包已接入多尺寸 [ICO 图标](apps/desktop/build/icon.ico)，来源见 [图标说明](docs/ICON.md)。

## 开发启动

需要 Node.js 22.12+、完整 JDK 17+；项目自带 Maven Wrapper。首次安装/构建需要联网下载依赖。

```bash
npm ci
npm run dev
```

`npm run dev` 自动构建 Java JAR，启动 React 开发服务和 Electron；Electron 管理本地 Java 服务。开发时可用 `COVERAGE_JAVA` 指定应用自身的 Java 程序，目标工程 JDK 在向导高级配置中单独选择。

```bash
npm run dev:ui        # 仅预览 React 界面；浏览器没有桌面目录/执行权限
npm run typecheck
npm run test:ui
npm run test:desktop
npm run test:backend  # 包含真实 Maven + JaCoCo 样例测试，需要系统 mvn 可用
npm run build
```

如果系统没有 `mvn`，测试时可以指定本机 Maven 程序：

```bash
node scripts/backend.mjs test -Dcoverage.test.maven=/path/to/maven/bin/mvn
# 内网构建可额外传入：-s /path/to/settings.xml -Dcoverage.test.settings=/path/to/settings.xml
```

Windows 上请用 `mvn.cmd` 的完整路径。

## v1.3.0 · 持续补测与故障恢复

默认开启 **持续运行直到达标**，旧配置缺失此字段时也采用该模式。原来的最多轮数、重复失败熔断不再自动结束持续任务；同一测试/编译问题达到次数后，改为延长等待再修复。关闭持续模式可恢复轮数限制和重复失败熔断。

- **API 波动**：探活失败、超时、连接中断、限流和服务端异常由外层工作流自动恢复。默认连续失败等待 **60 → 120 → 240 → 480 → 600 秒**，之后保持 600 秒；Agent 成功执行后重新从初始等待计数。初始/最长等待均可配置。
- **单轮超时**：默认 Agent 单轮总时限 30 分钟。达到时限后结束当前助手进程，保存输出、会话信息与测试文件变化，并立即进入下一轮 Maven 验证。已写入的代码继续参与测量；若已达标则完成，否则等待后用新 Agent 调用继续补测或修复。缺少 `BATCH_COMPLETE` 不会丢弃已经产生的修改。
- **两种停止方式**：“当前轮结束后停止”允许当前 Agent 操作结束（或达到单轮时限），随后验证它的修改再停止；基线/Maven 验证期间点击则在这次验证结束后停止，探活期间点击则在本次探活结束后停止。等待重试时点击会立即结束等待，不再启动工作。“立即停止”终止当前受管理的 Maven/Agent 进程，停止后续派生；已请求本轮后停止时仍可使用立即停止。
- **依赖与报告异常**：持续模式下等待后重新验证，不伪造覆盖率、不把依赖问题交给 Agent 修改测试；首轮 install 失败会在下一次验证重试 install，成功后继续保持后续轮次不重复 install。
- **运行证据**：看板及底部控制台显示等待原因、恢复次数和下次重试倒计时。恢复决定以 `[LOOP_RECOVERY]` 追加到对应 Maven/探活日志，Agent 记录保存 `failureKind`；轮次编号支持超过 999 轮。

外层恢复不会改写本地模型服务或 Hermes/OpenCode 自身的 HTTP 重试参数。应用退出、不可写的日志/数据库等无法继续运行的错误仍可能中断任务；已有记录按实际状态保留。恢复原生助手会话仍是独立功能，不代表应用重启后自动续跑。

## v1.2.3 · 自动补测启动引导

基线验证无需启用 Agent。未开启“启用自动补测”时，看板显示原因，并提供可点击的“配置 Agent 后补测”入口，直接打开补测策略页；开启并保存后即可点击“开始补测”。修改 Agent 配置时保留当前选择范围、Maven 配置和已有基线展示。自动补测仍作为新任务重新验证，不直接复用可能已过期的基线结果。

## v1.2.2 · 根 reactor 范围测试

修复内部依赖的已安装 POM 含 `${version_number}` 父版本时，预 install 成功、单模块测试却依赖解析失败的问题。正式测试保留根 POM、`-pl`、`-am`，多个选中模块共用一次 reactor；每个模块的测试范围由内置扩展独立控制，同名测试不会跨模块误执行。预构建强制跳过测试执行但保留测试编译，后续轮次不重复 install。已有版本号、settings、profiles、短仓库路径配置继续生效。

扩展字节码兼容 Java 8，随软件打包；运行时通过 `maven.ext.class.path` 加载，无需修改用户 POM 或安装额外插件。每轮 `round-NNN-scope` 保存独立测试清单与范围映射，完整 Maven 日志保存实际命令参数和工作目录。构建进度继续展示所选模块及其依赖。

依赖解析失败会显示“本轮覆盖率无效”，不建立覆盖率基线、不将待评估类作为未达标结论，持续模式会等待后重试构建，期间不会交给 Agent 改码；有轮数限制的模式仍停止。修正依赖后可由后续验证重新评估。一般编译错误和所选测试失败仍保留原有修复流程。对于不在根 reactor 内的外部依赖，仍需修复其发布 POM 或仓库配置；`-U` 无法修复版本占位符。

## v1.2.1 · 版本参数与记录删除

工程连接页和独立统计页直接提供 **Maven 版本号（version_number）**，填写后向预构建 install、基线与每轮测试统一传入 `-Dversion_number=<值>`。默认留空，沿用工程配置。旁边的 **强制更新依赖（-U）** 开关默认关闭；开启后重新检查此前缺失的依赖。它不会修复发布 POM 内无法解析的父版本。原额外参数中的 `-Dversion_number=...`、`-U` / `--update-snapshots` 自动迁移到新字段，避免重复传参。

运行记录右侧的删除按钮可删除该任务及全部轮次数据；确认区可勾选同时清理本次日志与报告。默认保留磁盘文件，项目配置、源码与生成的测试源码始终保留。正在运行或停止中的记录不能删除，恢复会话终端打开期间也暂不允许删除。删除当前任务后，看板、统计结果和底部运行台同步清空；其他任务不受影响。

文件清理仅使用记录里保存的本次运行目录或探活日志，拒绝越界路径和其他记录仍引用的目录。清理前先移动文件，数据库删除失败则恢复；文件被占用导致无法清理时显示具体原因。

## v1.2 运行台与轮次记录

- 底部常驻控制台，可展开/收起；收起时保留运行波形、当前模块与阶段、耗时和模块完成数量。实时输出可关闭自动跟随；模块页展示预构建与范围测试各模块的等待、运行、成功、失败和跳过状态。进度来自 Maven 输出，不用耗时估算百分比。
- 包树按父子关系组织：`api` 自身的类和 `api.conf`、`api.dep` 子包位于同一父节点下。父包勾选包含其子包；统计树逐层累加行数，不重复统计。
- 看板中的未达标类可独立折叠。下方“打开本轮记录”提供 `coverage.json`、`coverage-gate.txt`、`failed-classes.txt`、`maven.log`、`summary.txt` 和 `session.json` 标签页。`summary.txt` 对应磁盘上的 `round-NNN-test-summary.txt`；其余文件也绑定所选轮次，不会跳到最新轮。
- 六种文件在应用内只读预览，单文件最多显示前 512 KB，也可打开目录查看全文。旧版本没有独立轮次 session 文件时说明缺失，不拿最新 session 冒充历史记录。
- 浅绿界面增加卡片入场、图表生长、选中标签滑动、折叠过渡和运行波形；支持系统“减少动态效果”。

### Maven 构建和测试判定

每个任务默认在首轮对所选模块及上游依赖执行 `install -DskipTests=true`，保留测试编译以兼容 test-jar 依赖；不默认 `clean`。后续补测轮只运行范围内的测试与覆盖率。已有依赖时可关闭预构建。普通 `test` 阶段不会触发绑定在 `package` 的 ProGuard；首轮 install 仍会经过 ProGuard，短仓库路径配置仍有效。

测试清单之外的测试不执行。范围内测试失败时继续采集其他选中模块，并最终明确标为失败/需修复；自动补测进入修复轮，不能因 Maven 忽略失败而判为达标。编译或依赖错误仍是构建失败。没有测试文件的模块保留为“未测”，不阻断其他模块统计，并允许自动补测为其生成测试；缺少有效报告的模块不会显示伪造覆盖率。

### Recover session

结束任务后，可在所选轮次的记录区点击 **Recover session**，在 Windows PowerShell 终端继续 Hermes / OpenCode 的原生会话。应用记录助手实际返回的会话 ID，并使用明确命令：

```text
hermes chat --resume <session-id>
opencode --session <session-id>
```

Hermes 的 profile 和 OpenCode 的 attach 配置来自当时保存的配置。恢复终端在原工程目录启动；恢复期间请在该终端完成操作并关闭窗口，再启动新的桌面任务。此操作恢复助手对话，新的改动需要回到桌面重新运行验证；不会自动回滚工程文件或自动续跑 Maven 循环。

每个 Agent 补测轮与其前置验证轮关联。后续验证轮可恢复最近的相关 Agent 会话，界面显示会话来源轮次。仅 Maven 基线或没有原生会话 ID 的旧记录会禁用按钮并说明原因，不使用“最近一次会话”猜测。凭据仍由本机 Agent CLI 管理。

命令依据：[Hermes 会话文档](https://hermes-agent.nousresearch.com/docs/user-guide/sessions)、[OpenCode CLI](https://opencode.ai/docs/cli/)。

## Windows 安装包

在 Windows x64 上安装 Node.js 与完整 JDK 17+，设置 `JAVA_HOME`：

```powershell
npm ci
npm run package:win
```

输出在 `release/`。脚本使用 `jlink` 生成应用运行时，再将 React、Java 内核和运行时一起打包；用户无需为了启动应用另外安装 Java。但被测试工程仍需要匹配的 JDK、可用的 Maven / Wrapper，以及已经安装和配置好的 Hermes 或 OpenCode。

仓库 CI 提供 Windows 构建与安装包 artifact；默认不发布 Release、不签名。

## Aceternity UI 配置

`apps/desktop/components.json` 沿用 RaySnapCF 的 `@aceternity` registry、neutral、Lucide 和别名习惯，并适配 Vite（`rsc: false`）。

手动使用 shadcn CLI 安装需要鉴权的组件时，将 `.env.example` 复制为 `.env.local`，在本机填写 `ACETERNITY_API_KEY`。CLI 从环境引用密钥；不要使用 `VITE_` 前缀，不要把真实密钥写进 `components.json` 或提交 Git。

```bash
cd apps/desktop
npx shadcn@latest add @aceternity/bento-grid
```

`npm ci` 会以无鉴权请求从官方 registry 下载哈希锁定的公开组件到本机，下载文件不纳入 Git。网络失败后可运行 `npm run ui:sync` 重试。

目前界面实际使用 Aceternity 的 Bento Grid 与 Hover Border Gradient；基础表单和树按桌面任务流程实现。来源和改动见 [第三方组件说明](docs/THIRD-PARTY.md)。

## 数据与行为

- 数据库位于 Electron 用户数据目录的 `workspace.db`，采用 SQLite WAL。开发时 Java 服务可用 `COVERAGE_DATA_DIR` 指定独立数据目录。
- 原始日志、提示词、Surefire 归档与覆盖率快照在目标工程的 `.coverage-loop/`。
- 已有旧版配置首次打开工程时导入 SQLite；旧文件保留。
- 第一轮建立基线；DT 变化是执行用例数差值，参数化测试可能按多条计数。
- 缺少有效 JaCoCo 报告时不显示真实覆盖率，也不判定达标。
- 退出/取消会停止应用管理的 Maven / Agent 子进程；手动恢复终端需自行关闭。意外退出后任务标记为中断，历史数据可回看；不会自动恢复执行。
- 默认仅要求 Agent 修改 `src/test`，这不是系统级文件隔离。请在独立分支/工作树运行，并在提交前检查全仓库 diff。
- 当前界面支持行覆盖率；不支持分支门禁、自动断点续跑或将 DT 精确归属到生产类。

详细架构、统计口径、清理范围和后续计划见 [重建方案](docs/REBUILD-PLAN.md)。
