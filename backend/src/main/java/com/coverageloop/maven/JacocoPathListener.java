package com.coverageloop.maven;

import org.apache.maven.execution.MojoExecutionEvent;
import org.apache.maven.execution.MojoExecutionListener;
import org.apache.maven.plugin.MojoExecutionException;
import java.io.File;
import java.nio.file.Path;
import java.util.*;

/** Avoid passing an absolute Unicode JaCoCo destination to the forked Java launcher. */
public final class JacocoPathListener implements MojoExecutionListener {
    @Override public void beforeMojoExecution(MojoExecutionEvent event) throws MojoExecutionException {
        if (event.getSession().getUserProperties().getProperty("coverage.loop.scope") == null) return;
        if (!"org.apache.maven.plugins".equals(event.getExecution().getGroupId())) return;
        String plugin = event.getExecution().getArtifactId();
        if (!plugin.equals("maven-surefire-plugin") && !plugin.equals("maven-failsafe-plugin")) return;
        Object mojo = event.getMojo();
        try {
            // These public Surefire accessors expose the effective configuration, including
            // execution overrides. Reflection keeps plugin implementation jars out of the extension.
            File working = (File) mojo.getClass().getMethod("getWorkingDirectory").invoke(mojo);
            if (working == null) working = event.getProject().getBasedir();
            Path directory = working.toPath().toAbsolutePath().normalize();
            Path destination = new File(event.getProject().getBuild().getDirectory(), "jacoco.exec").toPath().toAbsolutePath().normalize();
            if (!Objects.equals(directory.getRoot(), destination.getRoot())) return;
            String absolute = destination.toString(), relative = directory.relativize(destination).toString().replace('\\', '/');
            Properties properties = event.getProject().getProperties();
            boolean changed = false;
            for (String key : new HashSet<String>(properties.stringPropertyNames())) {
                String value = properties.getProperty(key);
                String replacement = replace(value, absolute, relative);
                changed |= !Objects.equals(value, replacement); properties.setProperty(key, replacement);
            }
            String argLine = (String) mojo.getClass().getMethod("getArgLine").invoke(mojo);
            if (argLine != null) {
                String replacement = replace(argLine, absolute, relative); changed |= !argLine.equals(replacement);
                mojo.getClass().getMethod("setArgLine", String.class).invoke(mojo, replacement);
            }
            if (changed) System.out.println("[INFO] [coverage-loop] JaCoCo output: " + relative + " (relative to test working directory)");
        } catch (NoSuchMethodException ignored) {
            // Non-test goals (e.g. failsafe:verify) do not launch a forked VM.
        } catch (Exception error) {
            throw new MojoExecutionException("Could not prepare JaCoCo output for the test JVM", error);
        }
    }
    private static String replace(String value, String absolute, String relative) {
        if (value == null || !value.contains("-javaagent:") || !value.contains("jacoco")) return value;
        return value.replace("destfile=" + absolute.replace("\\", "\\\\"), "destfile=" + relative)
                .replace("destfile=" + absolute, "destfile=" + relative)
                .replace("destfile=" + absolute.replace('\\', '/'), "destfile=" + relative);
    }
    @Override public void afterMojoExecutionSuccess(MojoExecutionEvent event) {}
    @Override public void afterExecutionFailure(MojoExecutionEvent event) {}
}
