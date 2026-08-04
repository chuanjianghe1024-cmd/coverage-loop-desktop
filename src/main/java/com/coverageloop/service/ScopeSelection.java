package com.coverageloop.service;

import com.coverageloop.model.CoverageScope;
import com.coverageloop.model.JavaClassInfo;
import com.coverageloop.model.ScopeKind;
import com.coverageloop.model.ScopeMode;

import java.util.List;

/** Include / Exclude 范围选择逻辑，与 Electron 版 scope-selection.ts 一致 */
public final class ScopeSelection {

    private ScopeSelection() {
    }

    public static boolean scopeMatchesClass(CoverageScope scope, String qualifiedName) {
        if (scope.kind == ScopeKind.package_) {
            return qualifiedName.startsWith(scope.pattern + ".");
        }
        return qualifiedName.equals(scope.pattern) || qualifiedName.startsWith(scope.pattern + "$");
    }

    public static boolean isSelectedClass(List<CoverageScope> scopes, String qualifiedName) {
        List<CoverageScope> includes = scopes.stream().filter(s -> s.mode == ScopeMode.include).toList();
        List<CoverageScope> excludes = scopes.stream().filter(s -> s.mode == ScopeMode.exclude).toList();
        boolean included = includes.isEmpty() || includes.stream().anyMatch(s -> scopeMatchesClass(s, qualifiedName));
        return included && excludes.stream().noneMatch(s -> scopeMatchesClass(s, qualifiedName));
    }

    private static class ClassParts {
        final String packageName;
        final String className;

        ClassParts(String qualifiedName) {
            int separator = qualifiedName.lastIndexOf('.');
            if (separator < 0) {
                packageName = "";
                className = qualifiedName;
            } else {
                packageName = qualifiedName.substring(0, separator);
                className = qualifiedName.substring(separator + 1);
            }
        }
    }

    private static boolean testClassMatchesBusinessClass(JavaClassInfo testClass, String businessQualifiedName) {
        ClassParts business = new ClassParts(businessQualifiedName);
        if (!testClass.packageName.equals(business.packageName)) return false;
        return testClass.name.equals(business.className)
                || testClass.name.equals(business.className + "Test")
                || testClass.name.equals(business.className + "Tests")
                || testClass.name.equals(business.className + "TestCase")
                || testClass.name.equals(business.className + "IT")
                || testClass.name.equals("Test" + business.className);
    }

    private static boolean scopeMatchesTestClass(CoverageScope scope, JavaClassInfo testClass) {
        if (scope.kind == ScopeKind.package_) {
            return scopeMatchesClass(scope, testClass.qualifiedName);
        }
        return testClassMatchesBusinessClass(testClass, scope.pattern);
    }

    public static boolean isSelectedTestClass(List<CoverageScope> scopes, JavaClassInfo testClass) {
        List<CoverageScope> includes = scopes.stream().filter(s -> s.mode == ScopeMode.include).toList();
        List<CoverageScope> excludes = scopes.stream().filter(s -> s.mode == ScopeMode.exclude).toList();
        boolean included = includes.isEmpty() || includes.stream().anyMatch(s -> scopeMatchesTestClass(s, testClass));
        return included && excludes.stream().noneMatch(s -> scopeMatchesTestClass(s, testClass));
    }

    /** 统计当前规则命中的业务文件与测试文件数量 */
    public static int[] countSelectedFiles(List<CoverageScope> scopes, List<JavaClassInfo> classes,
                                           List<JavaClassInfo> testClasses) {
        int sourceFiles = 0;
        for (JavaClassInfo item : classes) {
            if (isSelectedClass(scopes, item.qualifiedName)) sourceFiles += 1;
        }
        int testFiles = 0;
        for (JavaClassInfo item : testClasses) {
            if (isSelectedTestClass(scopes, item)) testFiles += 1;
        }
        return new int[]{sourceFiles, testFiles};
    }
}
