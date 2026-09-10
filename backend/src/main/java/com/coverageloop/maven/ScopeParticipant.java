package com.coverageloop.maven;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

/** Loaded only by the target Maven process. Keep Java 8 compatibility for target JDKs. */
public final class ScopeParticipant extends AbstractMavenLifecycleParticipant {
    @Override public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        String filename = session.getUserProperties().getProperty("coverage.loop.scope");
        if (filename == null) return;
        try {
            Path file = Paths.get(filename);
            Properties plan = new Properties();
            try (InputStream input = Files.newInputStream(file)) { plan.load(input); }
            Map<Path,String> selected = new HashMap<>();
            boolean preinstall = Boolean.parseBoolean(session.getUserProperties().getProperty("coverage.loop.preinstall"));
            for (int i = 1; i <= Integer.parseInt(plan.getProperty("count")); i++)
                selected.put(Paths.get(plan.getProperty(i + ".directory")).toRealPath(), String.valueOf(i));
            Set<Path> applied = new HashSet<>();
            for (MavenProject project : session.getProjects()) {
                Path directory = project.getBasedir().toPath().toRealPath();
                String index = selected.get(directory);
                boolean run = !preinstall && index != null && Boolean.parseBoolean(plan.getProperty(index + ".tests"));
                if (index != null) applied.add(directory);
                for (String artifact : Arrays.asList("maven-surefire-plugin", "maven-failsafe-plugin")) {
                    Plugin plugin = project.getPlugin("org.apache.maven.plugins:" + artifact);
                    if (plugin == null) {
                        if (artifact.equals("maven-failsafe-plugin")) continue;
                        plugin = new Plugin(); plugin.setGroupId("org.apache.maven.plugins"); plugin.setArtifactId(artifact);
                        project.getBuild().addPlugin(plugin);
                    }
                    // Every execution gets an independent configuration, including inherited overrides.
                    plugin.setConfiguration(configure(plugin.getConfiguration(), run, plan, index));
                    for (PluginExecution execution : plugin.getExecutions())
                        execution.setConfiguration(configure(execution.getConfiguration(), run, plan, index));
                }
                System.out.println("[INFO] [coverage-loop] " + project.getArtifactId() + (run ? ": selected tests" : ": tests skipped (dependency or empty selection)"));
            }
            if (!applied.equals(selected.keySet())) throw new IllegalStateException("Some selected modules are missing from the reactor");
            Files.write(Paths.get(filename + ".ready"), Collections.singletonList("scope applied"));
        } catch (Exception error) {
            throw new MavenExecutionException("Coverage Loop could not apply module test scope: " + error.getMessage(), error);
        }
    }

    private static Xpp3Dom configure(Object original, boolean run, Properties plan, String index) {
        Xpp3Dom config = original instanceof Xpp3Dom ? new Xpp3Dom((Xpp3Dom) original) : new Xpp3Dom("configuration");
        set(config, "skipTests", String.valueOf(!run));
        set(config, "skip", String.valueOf(!run));
        set(config, "skipITs", String.valueOf(!run));
        set(config, "failIfNoTests", "false");
        set(config, "failIfNoSpecifiedTests", "false");
        // Explicit configuration wins over broad POM includes and user-property selectors.
        set(config, "test", ""); set(config, "includes", ""); set(config, "excludes", "");
        set(config, "includesFile", run ? plan.getProperty(index + ".includes") : "");
        set(config, "excludesFile", run ? plan.getProperty(index + ".excludes") : "");
        return config;
    }
    private static void set(Xpp3Dom config, String name, String value) {
        while (config.getChild(name) != null) config.removeChild(config.getChild(name));
        Xpp3Dom child = new Xpp3Dom(name); child.setValue(value);
        child.setAttribute("combine.self", "override"); config.addChild(child);
    }
}
