package com.jijing.fund.knowledge.service;

import com.jijing.fund.knowledge.api.IndexRebuildView;
import com.jijing.fund.knowledge.port.IndexGovernanceGateway;
import com.jijing.fund.knowledge.port.IndexRebuildRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 锁定索引重建在条数不一致和非法状态切换上的现有行为。
 */
class KnowledgeIndexGovernanceReadabilityGapTest {
    private final Instant now = Instant.parse("2026-08-23T00:00:00Z");
    private final MemoryRepository repository = new MemoryRepository();
    private final RecordingGateway gateway = new RecordingGateway();
    private final KnowledgeIndexGovernanceService service = new KnowledgeIndexGovernanceService(
            gateway, repository, Clock.fixed(now, ZoneOffset.UTC), "emb-v1", "chunk-v1");

    /**
     * 期望条数和写入条数不同时记录失败并返回视图，不把创建变成异常。
     */
    @Test
    void countMismatchIsRecordedWithoutThrowing() {
        gateway.expected = 4;
        gateway.indexed = 3;
        IndexRebuildView view = service.create();
        assertThat(view.errorCode()).isEqualTo("INDEX_COUNT_MISMATCH");
        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(repository.readyCalled).isFalse();
        assertThat(gateway.activated).isFalse();
    }

    /**
     * 不是待激活状态时拒绝切换，网关不会改别名。
     */
    @Test
    void activateRejectsARebuildThatIsNotReady() {
        repository.seed("rebuild-1", "BUILDING");
        assertThatThrownBy(() -> service.activate("rebuild-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Index rebuild is not ready to activate");
        assertThat(gateway.activated).isFalse();
    }

    /**
     * 待激活的重建会把读别名中的 _read 换成 _write 后切换。
     */
    @Test
    void activateRewritesTheReadAliasBeforeSwitching() {
        repository.seed("rebuild-1", "READY_TO_ACTIVATE");
        IndexRebuildView view = service.activate("rebuild-1");
        assertThat(gateway.readAlias).isEqualTo("fund_read");
        assertThat(gateway.writeAlias).isEqualTo("fund_write");
        assertThat(view.status()).isEqualTo("ACTIVE");
    }

    /**
     * 尚未生效的重建不能回滚。
     */
    @Test
    void rollbackRejectsARebuildThatIsNotActive() {
        repository.seed("rebuild-1", "READY_TO_ACTIVATE");
        assertThatThrownBy(() -> service.rollback("rebuild-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only active rebuild can be rolled back");
        assertThat(gateway.rolledBack).isFalse();
    }

    /**
     * 记录别名切换参数。条数由用例改写，用来制造不一致。
     */
    private static final class RecordingGateway implements IndexGovernanceGateway {
        private long expected = 1;
        private long indexed = 1;
        private boolean activated;
        private boolean rolledBack;
        private String readAlias;
        private String writeAlias;

        /**
         * 返回固定别名和用例指定的条数。
         */
        @Override
        public PreparedIndex rebuild(String rebuildId) {
            return new PreparedIndex("fund_read", "fund_write", "idx-old", "idx-new", expected, indexed, Map.of());
        }

        /**
         * 记下本次切换使用的读写别名。
         */
        @Override
        public void activate(String readAlias, String writeAlias, String previousIndex, String targetIndex) {
            this.activated = true;
            this.readAlias = readAlias;
            this.writeAlias = writeAlias;
        }

        /**
         * 记下回滚已经被调用。
         */
        @Override
        public void rollback(String readAlias, String writeAlias, String currentIndex, String previousIndex) {
            this.rolledBack = true;
        }
    }

    /**
     * 用内存保存重建视图。失败时把状态写成 FAILED，就绪时写成 READY_TO_ACTIVATE。
     */
    private static final class MemoryRepository implements IndexRebuildRepository {
        private final Map<String, IndexRebuildView> rows = new HashMap<>();
        private boolean readyCalled;

        /**
         * 放入一条指定状态的重建，供激活和回滚用例使用。
         */
        private void seed(String id, String status) {
            rows.put(id, view(id, status, null));
        }

        /**
         * 创建一条尚未完成的记录。
         */
        @Override
        public void create(
                String id,
                String alias,
                String previous,
                String target,
                String embeddingVersion,
                String chunkingVersion,
                Instant at) {
            rows.put(id, view(id, "CREATING", null));
        }

        /**
         * 把记录标成可以激活。
         */
        @Override
        public void ready(String id, long expected, long indexedCount, Map<String, Object> report, Instant at) {
            readyCalled = true;
            rows.put(id, view(id, "READY_TO_ACTIVATE", null));
        }

        /**
         * 仅当当前状态等于期望值时改写状态。
         */
        @Override
        public void status(String id, String expectedStatus, String status, Instant at) {
            IndexRebuildView current = rows.get(id);
            if (!expectedStatus.equals(current.status())) {
                throw new IllegalStateException("status mismatch");
            }
            rows.put(id, view(id, status, current.errorCode()));
        }

        /**
         * 记下条数不一致，并把状态留给后续读取。
         */
        @Override
        public void fail(String id, String code, Map<String, Object> report, Instant at) {
            rows.put(id, view(id, "FAILED", code));
        }

        /**
         * 返回当前记录。
         */
        @Override
        public IndexRebuildView find(String id) {
            return rows.get(id);
        }

        /**
         * 组装测试用的重建视图，索引名保持固定。
         */
        private static IndexRebuildView view(String id, String status, String errorCode) {
            return new IndexRebuildView(
                    id,
                    "fund_read",
                    "idx-old",
                    "idx-new",
                    "emb-v1",
                    "chunk-v1",
                    status,
                    4L,
                    3L,
                    Map.of(),
                    errorCode,
                    Instant.parse("2026-08-23T00:00:00Z"),
                    null);
        }
    }
}
