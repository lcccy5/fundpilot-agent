package com.jijing.fund.domain.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证 {@link ImportBatch} 对导入行列表的规范化。 */
class ImportBatchTest {
    /** 用给定导入行构造一个预览状态的批次，其余字段取合法默认值。 */
    private static ImportBatch batch(List<ImportRow> rows) {
        return new ImportBatch("b1", PortfolioId.random(), null, "sha", "trades.csv", ImportBatchStatus.PREVIEWED,
                1, 1, 0, Instant.EPOCH, null, rows);
    }

    /** 导入行为 null 时视为没有行。 */
    @Test
    void nullRowsBecomeEmpty() {
        assertThat(batch(null).rows()).isEmpty();
    }

    /** 构造后修改原列表不影响批次，且批次暴露的列表不可修改。 */
    @Test
    void rowsAreDefensivelyCopied() {
        List<ImportRow> rows = new ArrayList<>(List.of(new ImportRow(1, "{}", null, null)));
        var batch = batch(rows);
        rows.add(new ImportRow(2, "{}", "INVALID", "bad row"));

        assertThat(batch.rows()).hasSize(1);
        assertThatThrownBy(() -> batch.rows().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    /** 导入行列表含 null 元素时抛出 NullPointerException。 */
    @Test
    void rejectsNullRowElement() {
        assertThatThrownBy(() -> batch(Arrays.asList(new ImportRow(1, "{}", null, null), null)))
                .isInstanceOf(NullPointerException.class);
    }
}
