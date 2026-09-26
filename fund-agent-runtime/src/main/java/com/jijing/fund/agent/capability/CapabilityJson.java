package com.jijing.fund.agent.capability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * 能力执行器共用的任务输入解析、产物地址和证据标识生成。
 * 输入不是合法 JSON 或结果无法序列化时失败，不返回部分地址。
 */
final class CapabilityJson {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    /**
     * 禁止实例化工具类。
     * 不会失败。
     */
    private CapabilityJson() {
    }

    /**
     * 把任务输入解析成对象映射。
     * JSON 非法或映射器为空时抛出非法参数，调用方不得继续执行能力。
     */
    static Map<String, Object> input(ObjectMapper mapper, String json) {
        try {
            return mapper.readValue(json, MAP);
        } catch (Exception error) {
            throw new IllegalArgumentException("task input must be valid JSON", error);
        }
    }

    /**
     * 把结果序列化后生成带内容摘要的产物地址。
     * 序列化失败时抛出非法状态，不返回缺少摘要的地址。
     */
    static String artifactUri(ObjectMapper mapper, CapabilityExecutionContext context, Object value) {
        try {
            String json = mapper.writeValueAsString(value);
            return "artifact://" + context.task().planId() + "/" + context.task().taskKey() + "#"
                    + sha(json).substring(0, 16);
        } catch (Exception error) {
            throw new IllegalStateException("cannot serialize capability result", error);
        }
    }

    /**
     * 用前缀和值的摘要生成稳定证据标识。
     * 摘要计算失败时抛出非法状态。
     */
    static String evidenceId(String prefix, String value) {
        return prefix + "-" + sha(value).substring(0, 24);
    }

    /**
     * 计算 UTF-8 文本的 SHA-256 十六进制摘要。
     * 算法不可用时抛出非法状态。
     */
    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("cannot hash capability value", error);
        }
    }
}
