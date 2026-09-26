package com.jijing.fund.interfaces.web;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.jijing.fund.domain.model.FundCode;
import java.io.IOException;
import org.springframework.boot.jackson.JsonComponent;

/**
 * 在 JSON 和缓存中把基金代码显示为六位字符串，同时继续使用领域值对象。
 * 反序列化时沿用值对象的校验，非法文本会抛出参数异常。
 */
@JsonComponent
public class FundCodeJsonComponent {
    /**
     * 把基金代码写成 JSON 字符串。
     */
    public static class Serializer extends JsonSerializer<FundCode> {
        /**
         * 输出值对象中的六位代码，不额外包一层对象。
         */
        @Override
        public void serialize(FundCode value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
            generator.writeString(value.value());
        }
    }

    /**
     * 从 JSON 字符串恢复基金代码。
     */
    public static class Deserializer extends JsonDeserializer<FundCode> {
        /**
         * 交给 {@link FundCode} 校验长度和字符。空值或非六位数字会失败。
         */
        @Override
        public FundCode deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return new FundCode(parser.getValueAsString());
        }
    }
}
