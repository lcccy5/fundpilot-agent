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
 * Produces a compact, query-aware view of a stored tool result.
 *
 * <p>The fact card remains lossless in storage. Only the prompt-facing view is projected, so large
 * NAV histories and document hit lists cannot crowd every other useful memory out of the context
 * budget. This mirrors the abstraction/value split used by modern agent-memory systems without
 * introducing an LLM call into the memory read path.
 */
final class FundMemoryProjector {
    private static final Pattern ISO_DATE = Pattern.compile("(20\\d{2})[-/年](\\d{1,2})[-/月](\\d{1,2})(?:日)?");
    private static final int DEFAULT_ARRAY_ITEMS = 8;
    private static final int MAX_STRING_CHARS = 800;
    private final ObjectMapper mapper;

    FundMemoryProjector(ObjectMapper mapper) { this.mapper = mapper; }

    JsonNode project(FundMemoryCategory category, JsonNode value, String question) {
        return compact(value, category, question == null ? "" : question, 0);
    }

    private JsonNode compact(JsonNode value, FundMemoryCategory category, String question, int depth) {
        if (value == null || value.isNull() || depth > 12) return mapper.nullNode();
        if (value.isTextual()) {
            String text = value.asText();
            return mapper.getNodeFactory().textNode(text.length() <= MAX_STRING_CHARS
                    ? text : text.substring(0, MAX_STRING_CHARS) + "…");
        }
        if (value.isArray()) return compactArray((ArrayNode) value, category, question, depth);
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            value.properties().forEach(entry -> result.set(entry.getKey(),
                    compact(entry.getValue(), category, question, depth + 1)));
            return result;
        }
        return value.deepCopy();
    }

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
        for (int index : indexes) result.add(compact(source.get(index), category, question, depth + 1));
        return result;
    }

    private List<Integer> navIndexes(ArrayNode source, String question) {
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        Set<String> requestedDates = requestedDates(question);
        for (int i = 0; i < source.size(); i++) {
            JsonNode item = source.get(i);
            String date = firstText(item, "navDate", "date", "tradeDate");
            if (date != null && requestedDates.contains(date)) indexes.add(i);
        }
        if (!requestedDates.isEmpty() && !containsAny(question, "走势", "变化", "趋势")) {
            // An exact-date question needs the matching observation, not a miniature chart.
        } else if (containsAny(question, "最新", "期末", "最后", "当前", "截至")) {
            for (int i = Math.max(0, source.size() - 3); i < source.size(); i++) indexes.add(i);
        } else if (containsAny(question, "最早", "期初", "起始", "开始")) {
            for (int i = 0; i < Math.min(3, source.size()); i++) indexes.add(i);
        } else {
            indexes.addAll(evenlySpacedIndexes(source.size(), DEFAULT_ARRAY_ITEMS));
        }
        // Preserve boundary values even for a date-specific lookup; they make the represented range explicit.
        indexes.add(0);
        indexes.add(source.size() - 1);
        return indexes.stream().sorted().toList();
    }

    private Set<String> requestedDates(String question) {
        LinkedHashSet<String> dates = new LinkedHashSet<>();
        Matcher matcher = ISO_DATE.matcher(question);
        while (matcher.find()) {
            try {
                dates.add(LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3))).toString());
            } catch (RuntimeException ignored) { /* Invalid query dates cannot select a stored row. */ }
        }
        return dates;
    }

    private List<Integer> evenlySpacedIndexes(int size, int limit) {
        if (size <= limit) {
            List<Integer> all = new ArrayList<>(size);
            for (int i = 0; i < size; i++) all.add(i);
            return all;
        }
        LinkedHashSet<Integer> values = new LinkedHashSet<>();
        for (int i = 0; i < limit; i++) values.add((int) Math.round(i * (size - 1D) / (limit - 1D)));
        return List.copyOf(values);
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) return null;
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode()) return value.asText();
        }
        return null;
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }
}
