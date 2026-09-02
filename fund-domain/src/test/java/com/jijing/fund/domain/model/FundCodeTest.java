package com.jijing.fund.domain.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class FundCodeTest {
    @Test void acceptsSixDigits() { assertDoesNotThrow(() -> new FundCode("000001")); }
    @Test void rejectsInvalidCode() { assertThrows(IllegalArgumentException.class, () -> new FundCode("abc")); }
}

