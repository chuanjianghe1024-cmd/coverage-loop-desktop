# Coverage Loop Desktop（JavaFX 版）

面向 Maven 多模块 Java 项目的 Windows 桌面工具：选择根 `pom.xml` 后扫描 Reactor 模块、
配置包/类级 Include/Exclude 范围，运行 Maven + JaCoCo 生成覆盖率，并通过 Hermes / OpenCode
Agent 自动循环补充测试，直到目标类达到行覆盖率门槛。功能与 Electron 版
`E:\Users\HCJ\WebstormProjects\coverage-loop-desktop` 对齐（0.5.2）。

## 技术栈

- Java 17（`maven.compiler.release=17`）
- JavaFX 21 LTS（org.openjfx:javafx-controls:21.0.6）
- Gson 2.11（配置文件 JSON 读写，与 Electron 版格式兼容）
- Maven 3.9.x 构建，内置 Maven 工具链位于 `resources/toolchain/maven`（随仓库分发）

## 构建与运行

```bash
# 需要 JDK 17+（JAVA_HOME 指向本机 JDK）
mvn -q compile          # 编译
mvn test                # 单元 + 集成测试（集成测试会真实运行 Maven + JaCoCo）
mvn javafx:run          # 启动桌面应用（或直接运行 run.cmd）
```

内置 Maven 说明：程序按 内置（resources/toolchain/maven）→ 配置指定 → 项目 mvnw → 系统
MAVEN_HOME/M2_HOME/PATH 的顺序解析 mvn；JDK 优先使用配置的 JDK 根目录，其次 JAVA_HOME。

## 功能范围（与 Electron 版一致）

- 递归解析根 POM 的 `<modules>`，同时识别 Profile 中声明的模块。
- 扫描每个模块的 `src/main/java` 与 `src/test/java`，可按模块多选。
- 包/类可展开目录树配置 Include / Exclude，Exclude 优先；右侧实时显示命中的业务文件与测试文件数。
- 多套命名配置保存在 `.coverage-loop/configs/*.json`，兼容读取旧版 `project.json`。
- 支持公司 `settings.xml`、本地仓库、Profiles、Surefire 测试匹配、额外 `-D` 参数与并行线程。
- 每组新基线首轮先执行 `-DskipTests=true -Djacoco.skip=true install` 预构建（可在界面关闭）。
- 固定使用 JaCoCo 0.8.8：`prepare-agent → test → report`，默认追加
  `-DfailIfNoTests=false` 与 `-Dsurefire.failIfNoSpecifiedTests=false`。
- “补用例”与“修复失败用例”两套可编辑提示词模板，支持运行时占位符。
- 自动循环：Agent 探活（只回复 OK）→ Maven 基线 → Agent 补测/修复 → 下一轮 Maven；
  同一 Maven 失败按最后 80 条 `[ERROR]` 签名熔断。
- 每轮保存：Maven/Agent 日志、提示词、测试摘要、Surefire 归档、覆盖率门禁、待补类清单、
  测试变更清单、覆盖率 JSON 快照与 session.json。
- 第 1 轮建立覆盖率基线；后续轮次分组为 初始已满足 / 待补充 / 已补充。
- 覆盖率统计：可建多个业务分组，跨模块选择包/类范围；`-pl ... -am` 只运行目标模块，
  固定 `-Dmaven.test.failure.ignore=true`；确认分组复用最近快照重算，跨分组去重。

## 目录结构

```text
src/main/java/com/coverageloop/
├── App.java / Launcher.java        # JavaFX 入口
├── model/                          # 数据结构（与 TS contracts 逐字段对齐）
├── service/                        # 核心服务（扫描/执行/覆盖率/循环/统计/配置存储）
├── ui/                             # 界面（MainView + 5 个视图 + AppContext 状态控制器）
└── util/                           # 文件/XML/JSON/进程/命名工具
src/test/java/com/coverageloop/     # 单元测试 + 真实 Maven 集成测试
src/test/resources/sample-project/  # 多模块样例项目（module-a / module-b）
```

## 与 Electron 版的差异

- 界面使用 JavaFX 原生控件与 CSS，布局与文案保持一致，无浏览器依赖。
- 打包方式不同：Electron 版有安装包与内置 Node 循环；本版由 Java 状态机直接驱动。
- 其余执行逻辑、目录结构（`.coverage-loop/`）与 JSON 格式完全兼容，可互换使用。
