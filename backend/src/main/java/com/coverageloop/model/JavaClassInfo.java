package com.coverageloop.model;

/** Java 源码文件信息 */
public class JavaClassInfo {
    public String name;
    public String packageName;
    public String qualifiedName;
    public String relativePath;

    public JavaClassInfo() {
    }

    public JavaClassInfo(String name, String packageName, String qualifiedName, String relativePath) {
        this.name = name;
        this.packageName = packageName;
        this.qualifiedName = qualifiedName;
        this.relativePath = relativePath;
    }
}
