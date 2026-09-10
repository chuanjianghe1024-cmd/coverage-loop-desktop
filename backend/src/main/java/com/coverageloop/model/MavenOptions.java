package com.coverageloop.model;

import java.util.ArrayList;
import java.util.List;

/** Maven 工具链与执行参数 */
public class MavenOptions {
    public boolean useBundledMaven = true;
    public boolean preInstall = true;
    public int parallelThreads = 1;
    public String executable = "";
    public String javaHome = "";
    public String settingsPath = "";
    public String localRepository = "";
    public String versionNumber = "";
    public boolean forceUpdate = false;
    public List<String> profiles = new ArrayList<>();
    public List<String> extraArgs = new ArrayList<>();
    public String testPattern = "";

    public MavenOptions copy() {
        MavenOptions copy = new MavenOptions();
        copy.useBundledMaven = useBundledMaven;
        copy.preInstall = preInstall;
        copy.parallelThreads = parallelThreads;
        copy.executable = executable;
        copy.javaHome = javaHome;
        copy.settingsPath = settingsPath;
        copy.localRepository = localRepository;
        copy.versionNumber = versionNumber;
        copy.forceUpdate = forceUpdate;
        copy.profiles = new ArrayList<>(profiles);
        copy.extraArgs = new ArrayList<>(extraArgs);
        copy.testPattern = testPattern;
        return copy;
    }
}
