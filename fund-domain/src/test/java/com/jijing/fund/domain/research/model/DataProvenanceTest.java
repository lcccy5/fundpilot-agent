package com.jijing.fund.domain.research.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证 {@link DataProvenance} 对来源地址的安全约束、必填字段以及版本和告警的规范化。 */
class DataProvenanceTest {
    private static final ProviderId PROVIDER = new ProviderId("tencent-quote");
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    /** 用给定来源地址构造一条行情来源，其余字段取合法默认值。 */
    private static DataProvenance withUri(String uri) {
        return new DataProvenance(PROVIDER, URI.create(uri), MarketDataKind.EXCHANGE_TRADED_QUOTE, "v1", null, NOW,
                QualityStatus.VERIFIED, null);
    }

    /** 用给定来源版本和质量告警构造一条合法来源。 */
    private static DataProvenance withVersionAndWarnings(String version, List<String> warnings) {
        return new DataProvenance(PROVIDER, URI.create("https://example.com/q"), MarketDataKind.EXCHANGE_TRADED_QUOTE,
                version, null, NOW, QualityStatus.PARTIAL, warnings);
    }

    /** http 和 https（不区分大小写）都被接受。 */
    @Test
    void acceptsHttpAndHttps() {
        assertThatCode(() -> withUri("http://example.com/q")).doesNotThrowAnyException();
        assertThatCode(() -> withUri("HTTPS://example.com/q")).doesNotThrowAnyException();
    }

    /** 非 http/https 协议或缺少协议的相对地址被拒绝。 */
    @Test
    void rejectsNonHttpScheme() {
        assertThatThrownBy(() -> withUri("ftp://example.com/q"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("http or https");
        assertThatThrownBy(() -> withUri("example.com/q"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("http or https");
    }

    /** 没有主机名或带用户信息（可能含密码）的地址被拒绝。 */
    @Test
    void rejectsMissingHostOrUserInfo() {
        assertThatThrownBy(() -> withUri("https:///q"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("host");
        assertThatThrownBy(() -> withUri("https://user:secret@example.com/q"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("user info");
    }

    /** 带片段的地址同样被拒绝。 */
    @Test
    void rejectsFragment() {
        assertThatThrownBy(() -> withUri("https://example.com/q#token"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fragments");
    }

    /** 提供方、地址、数据含义、采集时间、质量状态为 null 时抛出 NullPointerException。 */
    @Test
    void rejectsMissingRequiredFields() {
        URI uri = URI.create("https://example.com/q");
        MarketDataKind kind = MarketDataKind.EXCHANGE_TRADED_QUOTE;

        assertThatThrownBy(() -> new DataProvenance(null, uri, kind, "v1", null, NOW, QualityStatus.VERIFIED, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("providerId");
        assertThatThrownBy(() -> new DataProvenance(PROVIDER, null, kind, "v1", null, NOW, QualityStatus.VERIFIED, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("sourceUri");
        assertThatThrownBy(() -> new DataProvenance(PROVIDER, uri, null, "v1", null, NOW, QualityStatus.VERIFIED, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("dataKind");
        assertThatThrownBy(() -> new DataProvenance(PROVIDER, uri, kind, "v1", null, null, QualityStatus.VERIFIED, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("collectedAt");
        assertThatThrownBy(() -> new DataProvenance(PROVIDER, uri, kind, "v1", null, NOW, null, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("qualityStatus");
    }

    /** 来源版本缺失或空白时记为 unknown，否则去除首尾空白。 */
    @Test
    void normalizesSourceVersion() {
        assertThat(withVersionAndWarnings(null, null).sourceVersion()).isEqualTo("unknown");
        assertThat(withVersionAndWarnings("   ", null).sourceVersion()).isEqualTo("unknown");
        assertThat(withVersionAndWarnings(" v2 ", null).sourceVersion()).isEqualTo("v2");
    }

    /** 质量告警去除 null 和空白项、去除首尾空白并去重，结果不可修改。 */
    @Test
    void normalizesQualityWarnings() {
        var provenance = withVersionAndWarnings("v1", Arrays.asList(" STALE ", null, "", "  ", "STALE", "PARTIAL"));

        assertThat(provenance.qualityWarnings()).containsExactly("STALE", "PARTIAL");
        assertThat(withVersionAndWarnings("v1", null).qualityWarnings()).isEmpty();
        assertThatThrownBy(() -> provenance.qualityWarnings().add("X")).isInstanceOf(UnsupportedOperationException.class);
    }
}
