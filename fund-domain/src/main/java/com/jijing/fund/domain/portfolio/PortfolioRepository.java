package com.jijing.fund.domain.portfolio;

import com.jijing.fund.domain.identity.UserId;
import java.time.LocalDate;
import java.util.*;

public interface PortfolioRepository {
    List<UserPortfolio> findByOwner(UserId owner);
    Optional<UserPortfolio> findByIdAndOwner(PortfolioId id,UserId owner);
    void savePortfolio(UserPortfolio portfolio);
    boolean updateVersion(PortfolioId id,UserId owner,long expectedVersion,long nextVersion);
    Optional<FundTransaction> findByIdempotency(UserId owner,PortfolioId portfolioId,String idempotencyKey);
    Optional<FundTransaction> findTransaction(UserId owner,PortfolioId portfolioId,String transactionId);
    boolean hasReversal(UserId owner,String originalTransactionId);
    void appendTransaction(FundTransaction transaction);
    List<FundTransaction> findTransactions(PortfolioId portfolioId,UserId owner);
    void saveImportBatch(ImportBatch batch);
    Optional<ImportBatch> findImportBatch(UserId owner,PortfolioId portfolioId,String batchId);
    void markImportCommitted(String batchId);
    void deleteImportBatch(String batchId);
    void replacePositionSnapshots(PortfolioId portfolioId,UserId owner,LocalDate asOf,List<FundPosition> positions,String inputHash);
    void deletePositionSnapshots(PortfolioId portfolioId,UserId owner);
    int countPositionSnapshots(PortfolioId portfolioId,UserId owner);
}
