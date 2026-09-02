package com.jijing.fund.interfaces.web;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.jijing.fund.domain.model.FundCode;
import java.io.IOException;
import org.springframework.boot.jackson.JsonComponent;

/** Keeps the domain value object while exposing a simple six-digit string in JSON and Redis. */
@JsonComponent
public class FundCodeJsonComponent {
    public static class Serializer extends JsonSerializer<FundCode> {
        @Override public void serialize(FundCode value, JsonGenerator generator, SerializerProvider serializers)
                throws IOException {
            generator.writeString(value.value());
        }
    }

    public static class Deserializer extends JsonDeserializer<FundCode> {
        @Override public FundCode deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return new FundCode(parser.getValueAsString());
        }
    }
}
