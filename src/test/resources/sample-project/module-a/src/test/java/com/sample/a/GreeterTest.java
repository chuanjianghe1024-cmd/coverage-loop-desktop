package com.sample.a;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GreeterTest {
    @Test
    void greetsWithPrefix() {
        Greeter greeter = new Greeter("你好，");
        assertEquals("你好，世界", greeter.greet(""));
        assertEquals("你好，Alice", greeter.greet("Alice"));
    }

    @Test
    void measuresLength() {
        Greeter greeter = new Greeter("x");
        assertEquals(0, greeter.length(null));
        assertEquals(5, greeter.length("hello"));
    }
}
