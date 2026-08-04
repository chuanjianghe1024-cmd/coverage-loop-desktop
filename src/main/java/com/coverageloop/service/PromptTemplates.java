package com.coverageloop.service;

/** 补用例 / 修复用例提示词模板，与 Electron 版完全一致 */
public final class PromptTemplates {

    private PromptTemplates() {
    }

    public static final String DEFAULT_COVERAGE_PROMPT_TEMPLATE = """
            执行第 {{round}} 轮 JaCoCo 覆盖率提升。

            项目根目录：
            {{projectRoot}}

            目标模块目录：
            {{moduleRoots}}

            根 Reactor POM：
            {{rootPom}}

            完整 Maven 日志：
            {{mavenLog}}

            最新覆盖率结果：
            {{coverageGate}}

            完整覆盖率快照：
            {{coverageSnapshot}}

            未达标类清单：
            {{failedClasses}}

            外层测试命令：
            {{testCommand}}

            本轮优先处理的类：
            {{targetClasses}}

            任务：
            1. 按未达标类清单顺序，只处理最前面的 {{batchSize}} 个类。
            2. 阅读对应生产代码、已有测试和未覆盖逻辑。
            3. 补充正常、异常、空值、边界和分支测试，必须包含有效断言。
            4. private 方法优先通过公开或 package-private 入口间接覆盖。
            5. 静态 Bean 获取优先使用 Mockito MockedStatic。
            6. Spring Bean 依赖使用项目已有测试基类、MockBean 或测试配置。
            7. 修改后运行必要的针对性测试；外层程序会重新运行 Reactor 测试并生成 JaCoCo 报告。
            8. 不得降低阈值、增加 exclusions、使用 Disabled、Ignore、skipTests 或删除已有测试和断言。
            9. 不得修改根 POM、依赖版本、仓库或 JaCoCo 配置。
            10. 不要询问下一批，不要等待确认，也不要宣称整体任务已经完成。
            11. 输出中禁止出现字符串 ALL_TARGETS_PASS。
            12. 本轮结束只输出 BATCH_COMPLETE。

            {{productionRule}}""";

    public static final String DEFAULT_REPAIR_PROMPT_TEMPLATE = """
            执行第 {{round}} 轮 Maven 测试修复。

            项目根目录：
            {{projectRoot}}

            目标模块目录：
            {{moduleRoots}}

            根 Reactor POM：
            {{rootPom}}

            完整测试日志：
            {{mavenLog}}

            本轮 Surefire 报告归档：
            {{surefireReports}}

            外层测试命令：
            {{testCommand}}

            任务：
            1. 必须先读取完整 Maven 日志和 Surefire 报告，定位真正失败原因。
            2. 如果是测试代码编译错误或测试失败，直接修改目标模块的测试代码并修复。
            3. 对 Mockito never、thenReturn 类型不匹配、基本类型取消引用等问题，必须以真实方法签名为准。
            4. 不得跳过、禁用或删除测试，不得降低断言质量。
            5. 不得修改根 POM、依赖版本、仓库、JaCoCo 阈值或排除规则。
            6. 修复后运行必要的针对性测试确认，不要运行整个 Reactor 的全量 test。
            7. 不要询问用户，不要等待确认。
            8. 本轮结束只输出 BATCH_COMPLETE。

            {{productionRule}}""";

    public static final String[] PROMPT_TEMPLATE_TOKENS = {
            "{{round}}", "{{projectRoot}}", "{{moduleRoots}}", "{{rootPom}}", "{{mavenLog}}",
            "{{surefireReports}}", "{{coverageSnapshot}}", "{{coverageGate}}", "{{failedClasses}}",
            "{{testCommand}}", "{{targetClasses}}", "{{batchSize}}", "{{productionRule}}"
    };
}
