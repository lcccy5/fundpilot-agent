package com.jijing.fund.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.infrastructure.persistence.entity.FundSyncRecordEntity;
import com.jijing.fund.infrastructure.persistence.mapper.FundSyncRecordMapper;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * 同步审计在缺行、未回填主键和主键冲突上的现有行为。映射器是假的，不执行 SQL。
 */
@ExtendWith(MockitoExtension.class)
class MybatisFundSyncAuditRepositoryReadabilityGapTest {
    @Mock
    private FundSyncRecordMapper mapper;

    @InjectMocks
    private MybatisFundSyncAuditRepository repository;

    @Test
    void missingAuditRowSkipsSuccessAndFailureUpdates() {
        when(mapper.selectById(42L)).thenReturn(null);
        when(mapper.selectById(0L)).thenReturn(null);

        repository.success(42L, 3, 2, Instant.EPOCH);
        repository.failure(0L, "SOURCE", "missing", Instant.EPOCH);

        verify(mapper).selectById(42L);
        verify(mapper).selectById(0L);
        verify(mapper, never()).updateById(any(FundSyncRecordEntity.class));
    }

    @Test
    void nullGeneratedIdFailsWhenTheInsertDoesNotFillIt() {
        when(mapper.insert(any(FundSyncRecordEntity.class))).thenReturn(1);

        assertThatThrownBy(() -> repository.start(new FundCode("000001"), "eastmoney", null, null))
                .isInstanceOf(NullPointerException.class);

        ArgumentCaptor<FundSyncRecordEntity> captor = ArgumentCaptor.forClass(FundSyncRecordEntity.class);
        verify(mapper).insert(captor.capture());
        FundSyncRecordEntity inserted = captor.getValue();
        assertThat(inserted.getId()).isNull();
        assertThat(inserted.getDateRangeStart()).isNull();
        assertThat(inserted.getDateRangeEnd()).isNull();
        assertThat(inserted.getErrorCode()).isNull();
        assertThat(inserted.getFinishedAt()).isNull();
        assertThat(inserted.getSyncStatus()).isEqualTo("RUNNING");
    }

    @Test
    void duplicatePrimaryKeyIsNotCaught() {
        when(mapper.insert(any(FundSyncRecordEntity.class))).thenThrow(new DuplicateKeyException("PRIMARY"));

        assertThatThrownBy(() -> repository.start(
                new FundCode("000001"),
                "eastmoney",
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 1, 31)))
                .isInstanceOf(DuplicateKeyException.class);
        verify(mapper, never()).updateById(any(FundSyncRecordEntity.class));
    }

    @Test
    void nullFailureDetailsAreStillHandedToTheUpdate() {
        FundSyncRecordEntity existing = new FundSyncRecordEntity();
        existing.setId(7L);
        existing.setErrorCode("OLD");
        existing.setErrorMessage("old message");
        when(mapper.selectById(7L)).thenReturn(existing);

        repository.failure(7L, null, null, null);

        ArgumentCaptor<FundSyncRecordEntity> captor = ArgumentCaptor.forClass(FundSyncRecordEntity.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getSyncStatus()).isEqualTo("FAILED");
        assertThat(captor.getValue().getErrorCode()).isNull();
        assertThat(captor.getValue().getErrorMessage()).isNull();
        assertThat(captor.getValue().getFinishedAt()).isNull();
    }
}
