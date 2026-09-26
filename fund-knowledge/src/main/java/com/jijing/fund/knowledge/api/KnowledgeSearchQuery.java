package com.jijing.fund.knowledge.api;

import com.jijing.fund.knowledge.domain.FundDocumentType;
import java.time.LocalDate;
import java.util.Set;

/**
 * 一次检索的过滤条件。基金代码和资料种类为 null 时收成空集，表示不按该维度过滤。
 * {@code topK} 小于等于 0 时改成 6，大于 10 时改成 10。检索预算使用的就是钳制后的条数，
 * 因此传入 0 并不会得到空结果，传入很大的值也不会超过 10 条。
 * 查询文本本身的空白和 500 字上限不在这里处理，由检索实现拒绝。
 */
public record KnowledgeSearchQuery(
        String query,
        Set<String> fundCodes,
        Set<FundDocumentType> documentTypes,
        LocalDate publishedAfter,
        LocalDate publishedBefore,
        int topK) {
    /**
     * 复制过滤集合，并把条数上限钳到 1 至 10，缺省为 6。
     */
    public KnowledgeSearchQuery {
        fundCodes = fundCodes == null ? Set.of() : Set.copyOf(fundCodes);
        documentTypes = documentTypes == null ? Set.of() : Set.copyOf(documentTypes);
        topK = topK <= 0 ? 6 : Math.min(topK, 10);
    }
}
