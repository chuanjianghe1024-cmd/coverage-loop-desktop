package com.coverageloop.service;

import com.coverageloop.model.*;
import com.coverageloop.util.Fs;
import java.nio.file.Path;
import java.util.*;

/** Production selection controls measurement; tests run within those production packages. */
public final class ScopedTests {
    private ScopedTests() {}
    public record Selection(String modulePath, int sourceCount, List<String> packages, List<String> tests) {}

    public static List<Selection> plan(ProjectConfig config) {
        ProjectScanResult project = ProjectScanner.scanMavenProject(config.rootPomPath);
        List<Selection> result = new ArrayList<>();
        for (MavenModule module : project.modules) {
            if (!config.selectedModulePaths.contains(module.relativePath)) continue;
            ModuleSources sources = ProjectScanner.scanModuleSources(module);
            List<CoverageScope> scopes = config.scopes.stream().filter(s -> module.relativePath.equals(s.modulePath)).toList();
            List<JavaClassInfo> selected = sources.classes.stream().filter(c -> ScopeSelection.isSelectedClass(scopes, c.qualifiedName)).toList();
            Set<String> packages = new TreeSet<>(selected.stream().map(c -> c.packageName).toList());
            List<String> tests = sources.testClasses.stream().filter(t -> scopes.isEmpty() || packages.contains(t.packageName))
                    .map(t -> t.qualifiedName).distinct().sorted().toList();
            result.add(new Selection(module.relativePath, selected.size(), new ArrayList<>(packages), tests));
        }
        return result;
    }

    public static List<String> writeFilters(Selection selection, Path directory, int index) {
        Path includes = directory.resolve("module-" + index + "-tests.include");
        Path excludes = directory.resolve("module-" + index + "-tests.exclude");
        List<String> names = selection.tests().stream().map(t -> t.replace('.', '/')).toList();
        Fs.writeString(includes.toString(), names.isEmpty() ? "__coverage_loop_no_matching_tests__.java\n"
                : String.join("\n", names.stream().map(n -> n + ".java").toList()) + "\n");
        // POM <includes> are appended to includesFile. The complement exclusion
        // prevents broad POM patterns from executing tests outside our selection.
        Fs.writeString(excludes.toString(), names.isEmpty() ? "**/*\n"
                : "%regex[" + exclusionRegex(names) + "]\n");
        return List.of("-Dsurefire.includesFile=" + includes.toAbsolutePath(), "-Dsurefire.excludesFile=" + excludes.toAbsolutePath());
    }

    // Surefire rejects '!' even inside a regex exclusion, so a negative lookahead
    // cannot express this complement. A prefix trie excludes every other class,
    // including generated tests absent from the source scan, without long CLI args.
    static String exclusionRegex(List<String> names) {
        Trie root = new Trie();
        for (String name : names) {
            root.add(name + ".class", false);
            root.add(name + "$", true); // Nested tests belong to the selected outer class.
        }
        return root.complement();
    }

    private static final class Trie {
        final Map<Character, Trie> children = new TreeMap<>();
        boolean terminal, anySuffix;
        void add(String text, boolean suffix) {
            Trie node = this;
            for (char c : text.toCharArray()) node = node.children.computeIfAbsent(c, ignored -> new Trie());
            node.terminal = true; node.anySuffix |= suffix;
        }
        String complement() {
            if (anySuffix) return null;
            if (children.isEmpty()) return terminal ? ".+" : ".*";
            // Surefire 3.2.x matches regexes against native Windows separators;
            // accept both forms, including the negated edge of the complement.
            String chars = String.join("", children.keySet().stream().map(Trie::characterSet).toList());
            List<String> alternatives = new ArrayList<>();
            alternatives.add("[^" + chars + "].*");
            for (var entry : children.entrySet()) {
                String rest = entry.getValue().complement();
                if (rest != null) alternatives.add("[" + characterSet(entry.getKey()) + "]" + rest);
            }
            if (!terminal) alternatives.add("");
            return "(?:" + String.join("|", alternatives) + ")";
        }
        static String characterSet(char c) {
            return c=='/' ? "\\x{2f}\\x{5c}" : "\\x{" + Integer.toHexString(c) + "}";
        }
    }
}
