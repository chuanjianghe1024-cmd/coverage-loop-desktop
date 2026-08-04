package com.sample.b;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalculatorTest {
    @Test
    void adds() {
        Calculator calculator = new Calculator();
        assertEquals(4, calculator.add(2, 2));
    }

    @Test
    void divides() {
        Calculator calculator = new Calculator();
        assertEquals(3, calculator.divide(9, 3));
    }

    @Test
    void evenNumbers() {
        Calculator calculator = new Calculator();
        assertTrue(calculator.isEven(4));
    }
}
