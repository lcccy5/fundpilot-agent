package com.jijing.fund.interfaces.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.jijing.fund.domain.model.FundCode;
import java.io.IOException;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

/**
 * 基金代码 JSON 组件对非法文本和空值的失败，以及合法代码的写出。
 */
class FundCodeJsonComponentReadabilityGapTest {
    /**
     * 不是六位数字的文本在反序列化时被值对象拒绝。
     */
    @Test
    void rejectsNonSixDigitCode() throws IOException {
        try (JsonParser parser = new JsonFactory().createParser("\"12\"")) {
            parser.nextToken();
            assertThrows(IllegalArgumentException.class,
                    () -> new FundCodeJsonComponent.Deserializer().deserialize(parser, null));
        }
    }

    /**
     * JSON null 不能变成基金代码。
     */
    @Test
    void rejectsNullToken() throws IOException {
        try (JsonParser parser = new JsonFactory().createParser("null")) {
            parser.nextToken();
            assertThrows(NullPointerException.class,
                    () -> new FundCodeJsonComponent.Deserializer().deserialize(parser, null));
        }
    }

    /**
     * 合法代码按字符串写出，不包额外对象。
     */
    @Test
    void writesSixDigitText() throws IOException {
        StringWriter writer = new StringWriter();
        try (JsonGenerator generator = new JsonFactory().createGenerator(writer)) {
            new FundCodeJsonComponent.Serializer().serialize(new FundCode("000001"), generator, null);
        }
        assertEquals("\"000001\"", writer.toString());
    }
}
