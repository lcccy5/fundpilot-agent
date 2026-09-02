package com.jijing.fund.application.portfolio;
import com.jijing.fund.domain.portfolio.PortfolioId;
public record SnapshotRebuildResult(PortfolioId portfolioId,String inputHash,int positionCount,int persistedCount){}
