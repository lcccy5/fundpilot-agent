package com.jijing.fund.agent.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 为写入提示词的工具结果生成紧凑、随问题变化的视图。
 *
 * <p>事实卡在存储中保持完整，只有面向提示词的视图被投影，避免大段净值或文档列表占满上下文。
 * 投影失败时退回截断文本或空节点，不让记忆读取拖垮本轮回答。
 * 计划、路由、审批或对等代理失败不由投影器重试或补数据。
 */
final class FundMemoryProjector {
    private static final Pattern ISO_DATE = Pattern.compile("(20\\d{2})[-/年](\\d{1,2})[-/月](\\d{1,2})(?:日)?");
    private static final int DEFAULT_ARRAY_ITEMS = 8;
    private static final int MAX_STRING_CHARS = 800;
    private final ObjectMapper mapper;

    /**
     * 绑定用于复制 JSON 的映射器。
     * 映射器由调用方保证可用；序列化问题在投影时降级，不在构造期失败。
     */
    FundMemoryProjector(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 按记忆分区和问题压缩一个 JSON 值。
     * 值为 null 时返回 JSON null。问题为 null 时按空问题处理，不因此丢弃整张事实卡。
     */
    JsonNode project(FundMemoryCategory category, JsonNode value, String question) {
        return compact(value, category, question == null ? "" : question, 0);
    }

    /**
     * 递归压缩对象、数组和长文本。
     * 深度超过 12 或值为 null 时返回 JSON null，防止异常深的工具结果撑爆提示词。
     */
    private JsonNode compact(JsonNode value, FundMemoryCategory category, String question, int depth) {
        if (value == null || value.isNull() || depth > 12) {
            return mapper.nullNode();
        }
        if (value.isTextual()) {
            String text = value.asText();
            return mapper.getNodeFactory().textNode(text.length() <= MAX_STRING_CHARS
                    ? text : text.substring(0, MAX_STRING_CHARS) + "…");
        }
        if (value.isArray()) {
            return compactArray((ArrayNode) value, category, question, depth);
        }
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            value.properties().forEach(entry -> result.set(entry.getKey(),
                    compact(entry.getValue(), category, question, depth + 1)));
            return result;
        }
        return value.deepCopy();
    }

    /**
     * 压缩数组。短数组原样保留；长净值序列按问题选点，其他长数组均匀抽样。
     * 抽样失败时不会回退成全量数组，以免重新撑满预算。
     */
    private JsonNode compactArray(ArrayNode source, FundMemoryCategory category, String question, int depth) {
        if (source.size() <= DEFAULT_ARRAY_ITEMS) {
            ArrayNode result = mapper.createArrayNode();
            source.forEach(item -> result.add(compact(item, category, question, depth + 1)));
            return result;
        }
        List<Integer> indexes = category == FundMemoryCategory.NAV
                ? navIndexes(source, question)
                : evenlySpacedIndexes(source.size(), category == FundMemoryCategory.DOCUMENTS ? 4 : DEFAULT_ARRAY_ITEMS);
        ArrayNode result = mapper.createArrayNode();
        for (int index : indexes) {
            result.add(compact(source.get(index), category, question, depth + 1));
        }
        return result;
    }

    /**
     * 为净值序列选择保留的下标。
     * 问题里的具体日期会优先命中；只问日期且不要求走势时不额外抽样。
     * 无论命中与否都保留首尾，使区间边界仍然可见。非法日期不选中任何行。
     */
    private List<Integer> navIndexes(ArrayNode source, String question) {
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        Set<String> requestedDates = requestedDates(question);
        for (int index = 0; index < source.size(); index++) {
            JsonNode item = source.get(index);
            String date = firstText(item, "navDate", "date", "tradeDate");
            if (date != null && requestedDates.contains(date)) {
                indexes.add(index);
            }
        }
        if (!requestedDates.isEmpty() && !containsAny(question, "走势", "变化", "趋势")) {
            // 精确日期问题只需要命中的观察，不需要一张缩小的走势图。
        } else if (containsAny(question, "最新", "期末", "最后", "当前", "截至")) {
            for (int index = Math.max(0, source.size() - 3); index < source.size(); index++) {
                indexes.add(index);
            }
        } else if (containsAny(question, "最早", "期初", "起始", "开始")) {
            for (int index = 0; index < Math.min(3, source.size()); index++) {
                indexes.add(index);
            }
        } else {
            indexes.addAll(evenlySpacedIndexes(source.size(), DEFAULT_ARRAY_ITEMS));
        }
        // 即使是按日期查找，也保留边界，让所代表的区间保持明确。
        indexes.add(0);
        indexes.add(source.size() - 1);
        return indexes.stream().sorted().toList();
    }

    /**
     * 从问题中解析请求的日期。
     * 无法构成合法日期的片段被忽略，不使投影失败。
     */
    private Set<String> requestedDates(String question) {
        LinkedHashSet<String> dates = new LinkedHashSet<>();
        Matcher matcher = ISO_DATE.matcher(question);
        while (matcher.find()) {
            try {
                dates.add(LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3))).toString());
            } catch (RuntimeException ignored) {
                // 问题里的非法日期不能选中存储行。
            }
        }
        return dates;
    }

    /**
     * 在指定长度内均匀取下标，并始终覆盖首尾。
     * 长度不超过上限时返回全部下标。
     */
    private List<Integer> evenlySpacedIndexes(int size, int limit) {
        if (size <= limit) {
            List<Integer> all = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                all.add(index);
            }
            return all;
        }
        LinkedHashSet<Integer> values = new LinkedHashSet<>();
        for (int index = 0; index < limit; index++) {
            values.add((int) Math.round(index * (size - 1D) / (limit - 1D)));
        }
        return List.copyOf(values);
    }

    /**
     * 返回对象上第一个有值的字段文本。
     * 节点不是对象或字段都不存在时返回 null，调用方跳过该行。
     */
    private String firstText(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode()) {
                return value.asText();
            }
        }
        return null;
    }

    /**
     * 判断文本是否包含任一关键词。
     * 都不包含时返回 false。
     */
    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
