package com.jijing.fund.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.model.FundProfile;
import com.jijing.fund.infrastructure.persistence.entity.FundEntity;
import com.jijing.fund.infrastructure.persistence.mapper.FundMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * 基金主数据仓储在缺行、空主键和重复代码上的现有行为。映射器是假的，不执行 SQL。
 */
@ExtendWith(MockitoExtension.class)
class MybatisFundRepositoryReadabilityGapTest {
    @Mock
    private FundMapper mapper;

    @InjectMocks
    private MybatisFundRepository repository;

    @Test
    void missingFundBecomesEmptyAndMissingRevisionBecomesZero() {
        when(mapper.findByCode("000001")).thenReturn(null);
        when(mapper.getDataRevision("000001")).thenReturn(null);
        when(mapper.findEnabled(0, 10)).thenReturn(List.of());
        when(mapper.incrementDataRevision("000001", null)).thenReturn(0);

        assertThat(repository.findByCode(new FundCode("000001"))).isEmpty();
        assertThat(repository.getDataRevision(new FundCode("000001"))).isZero();
        assertThat(repository.findEnabledFundCodes(0, 10)).isEmpty();
        repository.incrementDataRevision(new FundCode("000001"), null);

        verify(mapper).incrementDataRevision("000001", null);
    }

    @Test
    void storedZeroRevisionIsIndistinguishableFromMissingRow() {
        when(mapper.getDataRevision("000001")).thenReturn(0L);

        assertThat(repository.getDataRevision(new FundCode("000001"))).isZero();
    }

    @Test
    void nullIdentifiersFailBeforeAQueryAndANullSurrogateKeyStillMaps() {
        assertThatThrownBy(() -> repository.findByCode(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.getDataRevision(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.incrementDataRevision(null, LocalDate.of(2024, 1, 2)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.save(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(mapper);

        FundEntity row = storedFund();
        row.setId(null);
        when(mapper.findByCode("000001")).thenReturn(row);

        assertThat(repository.findByCode(new FundCode("000001"))).hasValueSatisfying(profile ->
                assertThat(profile.code().value()).isEqualTo("000001"));
    }

    @Test
    void nullFundCodeOnAnEnabledRowFailsTheWholePage() {
        FundEntity row = new FundEntity();
        row.setFundCode(null);
        when(mapper.findEnabled(0, 10)).thenReturn(List.of(row));

        assertThatThrownBy(() -> repository.findEnabledFundCodes(0, 10)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullEnabledPageFailsInsteadOfBecomingAnEmptyList() {
        when(mapper.findEnabled(0, 10)).thenReturn(null);

        assertThatThrownBy(() -> repository.findEnabledFundCodes(0, 10)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void saveLeavesTheSurrogateKeyNullForTheDatabaseToAssign() {
        repository.save(profile());

        ArgumentCaptor<FundEntity> captor = ArgumentCaptor.forClass(FundEntity.class);
        verify(mapper).upsert(captor.capture());
        assertThat(captor.getValue().getId()).isNull();
        assertThat(captor.getValue().getFundCode()).isEqualTo("000001");
        assertThat(captor.getValue().getDataRevision()).isNull();
        assertThat(captor.getValue().getLatestNavDate()).isNull();
    }

    @Test
    void duplicateFundCodeIsNotCaught() {
        when(mapper.upsert(any())).thenThrow(new DuplicateKeyException("uk_fund_code"));

        assertThatThrownBy(() -> repository.save(profile())).isInstanceOf(DuplicateKeyException.class);
        verify(mapper, never()).insert(org.mockito.ArgumentMatchers.any(FundEntity.class));
    }

    private static FundProfile profile() {
        return new FundProfile(
                new FundCode("000001"),
                "示例基金",
                null,
                null,
                null,
                null,
                "eastmoney",
                null,
                Instant.parse("2024-01-02T15:00:00Z"));
    }

    private static FundEntity storedFund() {
        FundEntity row = new FundEntity();
        row.setId(9L);
        row.setFundCode("000001");
        row.setFundName("示例基金");
        row.setDataSource("eastmoney");
        row.setCollectedAt(Instant.parse("2024-01-02T15:00:00Z"));
        return row;
    }
}
