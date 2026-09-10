package com.sample.a;

/** 示例业务类 */
public class Greeter {
    private final String prefix;

    public Greeter(String prefix) {
        this.prefix = prefix;
    }

    public String greet(String name) {
        if (name == null || name.isBlank()) {
            return prefix + "世界";
        }
        return prefix + name;
    }

    public int length(String value) {
        return value == null ? 0 : value.length();
    }
}
