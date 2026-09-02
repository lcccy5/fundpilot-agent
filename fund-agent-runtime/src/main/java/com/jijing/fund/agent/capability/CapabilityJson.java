package com.jijing.fund.agent.capability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** 实现 CapabilityJson 所代表的 Agent 运行时职责。 */
final class CapabilityJson {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    
    /** 执行该 Agent 运行时组件中的 CapabilityJson 操作。 */
    private CapabilityJson() {}

    static Map<String, Object> input(ObjectMapper mapper, String json) {
        try { return mapper.readValue(json, MAP); }
        catch (Exception error) { throw new IllegalArgumentException("task input must be valid JSON", error); }
    }

    static String artifactUri(ObjectMapper mapper, CapabilityExecutionContext context, Object value) {
        try {
            String json = mapper.writeValueAsString(value);
            return "artifact://" + context.task().planId() + "/" + context.task().taskKey() + "#" + sha(json).substring(0, 16);
        } catch (Exception error) { throw new IllegalStateException("cannot serialize capability result", error); }
    }

    static String evidenceId(String prefix, String value) { return prefix + "-" + sha(value).substring(0, 24); }

    
    /** 构造后续 Agent 处理所需的 sha 值。 */
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException("cannot hash capability value", error); }
    }
}
