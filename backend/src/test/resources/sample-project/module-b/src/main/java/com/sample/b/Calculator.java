package com.sample.b;

/** 示例业务类：计算器 */
public class Calculator {
    public int add(int a, int b) {
        return a + b;
    }

    public int divide(int a, int b) {
        return a / b;
    }

    public boolean isEven(int value) {
        return value % 2 == 0;
    }
}
