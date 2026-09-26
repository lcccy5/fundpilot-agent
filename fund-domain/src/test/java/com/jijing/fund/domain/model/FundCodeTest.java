package com.jijing.fund.domain.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

/** 验证 {@link FundCode} 只接受 6 位 ASCII 数字，且不会替调用方做去空白、补零等宽松处理。 */
class FundCodeTest {
    /** 标准 6 位数字代码可以正常构造。 */
    @Test void acceptsSixDigits() { assertDoesNotThrow(() -> new FundCode("000001")); }

    /** 含字母的代码被拒绝并抛出 IllegalArgumentException。 */
    @Test void rejectsInvalidCode() { assertThrows(IllegalArgumentException.class, () -> new FundCode("abc")); }

    /** null 代码抛出 NullPointerException，而不是 IllegalArgumentException。 */
    @Test void rejectsNull() { assertThrows(NullPointerException.class, () -> new FundCode(null)); }

    /** 空字符串不是合法代码。 */
    @Test void rejectsEmpty() { assertThrows(IllegalArgumentException.class, () -> new FundCode("")); }

    /** 少一位或多一位都被拒绝，不会自动补零或截断。 */
    @Test
    void rejectsWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> new FundCode("00001"));
        assertThrows(IllegalArgumentException.class, () -> new FundCode("0000001"));
    }

    /** 首尾空白和结尾换行不会被自动去除，因此都被拒绝。 */
    @Test
    void rejectsSurroundingWhitespace() {
        assertThrows(IllegalArgumentException.class, () -> new FundCode(" 000001"));
        assertThrows(IllegalArgumentException.class, () -> new FundCode("000001 "));
        assertThrows(IllegalArgumentException.class, () -> new FundCode("000001\n"));
    }

    /** 全角数字不属于 ASCII 数字，被拒绝。 */
    @Test void rejectsFullWidthDigits() { assertThrows(IllegalArgumentException.class, () -> new FundCode("０００００１")); }
}
