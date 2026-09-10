package com.coverageloop.service;

import com.coverageloop.model.ProjectConfig;
import com.coverageloop.util.Names;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** A small bundled core extension applies per-project test filters without editing user POMs. */
final class ReactorScope {
    private ReactorScope() {}
    static Path prepare(ProjectConfig config, RunHistory.PreparedRound round, List<String> arguments) throws IOException {
        Path directory = Files.createDirectories(Path.of(round.runDirectory, "round-" + Names.roundLabel(round.round) + "-scope"));
        Path extension = directory.resolve("reactor-scope.jar");
        String clazz = "com/coverageloop/maven/ScopeParticipant.class";
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(extension))) {
            jar.putNextEntry(new JarEntry(clazz));
            try (InputStream input = ReactorScope.class.getResourceAsStream("/" + clazz)) {
                if (input == null) throw new IOException("缺少 Maven 范围扩展，请重新安装软件");
                input.transferTo(jar);
            }
            jar.closeEntry(); jar.putNextEntry(new JarEntry("META-INF/plexus/components.xml"));
            jar.write(("<component-set><components><component><role>org.apache.maven.AbstractMavenLifecycleParticipant</role>"
                    + "<role-hint>coverage-loop-scope</role-hint><implementation>com.coverageloop.maven.ScopeParticipant</implementation>"
                    + "</component></components></component-set>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        List<ScopedTests.Selection> selections = ScopedTests.plan(config);
        Properties properties = new Properties(); properties.setProperty("count", String.valueOf(selections.size()));
        int i = 0;
        for (var selection : selections) {
            ScopedTests.writeFilters(selection, directory, ++i);
            properties.setProperty(i + ".directory", Path.of(config.rootPomPath).getParent().resolve(selection.modulePath()).toRealPath().toString());
            properties.setProperty(i + ".tests", String.valueOf(!selection.tests().isEmpty()));
            properties.setProperty(i + ".includes", directory.resolve("module-" + i + "-tests.include").toAbsolutePath().toString());
            properties.setProperty(i + ".excludes", directory.resolve("module-" + i + "-tests.exclude").toAbsolutePath().toString());
        }
        Path plan = directory.resolve("test-scope.properties");
        try (OutputStream output = Files.newOutputStream(plan)) { properties.store(output, "Coverage Loop reactor test scope"); }
        String prefix = "-Dmaven.ext.class.path=";
        String existing = arguments.stream().filter(a -> a.startsWith(prefix)).reduce((a,b) -> b).map(a -> a.substring(prefix.length())).orElse("");
        arguments.removeIf(a -> a.startsWith(prefix) || a.startsWith("-Dcoverage.loop.scope=") || a.startsWith("-Dcoverage.loop.preinstall="));
        arguments.add(prefix + (existing.isBlank() ? "" : existing + File.pathSeparator) + extension.toAbsolutePath());
        arguments.add("-Dcoverage.loop.scope=" + plan.toAbsolutePath());
        Path ready = Path.of(plan + ".ready"); Files.deleteIfExists(ready); return ready;
    }
}
