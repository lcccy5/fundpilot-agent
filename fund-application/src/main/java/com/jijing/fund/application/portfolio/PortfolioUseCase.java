package com.jijing.fund.application.portfolio;
import com.jijing.fund.domain.identity.*;import com.jijing.fund.domain.portfolio.*;import java.time.LocalDate;import java.util.*;
public interface PortfolioUseCase {
    List<UserPortfolio> list(AuthenticatedUser actor);UserPortfolio create(AuthenticatedUser actor,String name);
    FundTransaction append(AuthenticatedUser actor,PortfolioId id,TransactionCommand command);
    FundTransaction reverse(AuthenticatedUser actor,PortfolioId id,String transactionId,String idempotencyKey);
    List<FundTransaction> transactions(AuthenticatedUser actor,PortfolioId id);
    List<FundPosition> positions(AuthenticatedUser actor,PortfolioId id);
    SnapshotRebuildResult rebuildSnapshots(AuthenticatedUser actor,PortfolioId id);
    PortfolioValuation valuation(AuthenticatedUser actor,PortfolioId id,LocalDate asOf);
    PortfolioReturn returns(AuthenticatedUser actor,PortfolioId id,LocalDate asOf);
    PortfolioRiskView risk(AuthenticatedUser actor,PortfolioId id,LocalDate asOf);
    ImportBatch previewImport(AuthenticatedUser actor,PortfolioId id,String fileName,byte[] content);
    ImportBatch importBatch(AuthenticatedUser actor,PortfolioId id,String batchId);
    ImportBatch commitImport(AuthenticatedUser actor,PortfolioId id,String batchId,String fileSha256);
    void deleteImport(AuthenticatedUser actor,PortfolioId id,String batchId);
}
