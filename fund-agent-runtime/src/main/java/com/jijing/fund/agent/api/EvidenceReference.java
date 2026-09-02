package com.jijing.fund.agent.api;

import java.time.*;

/** 在 Agent 运行时边界间传递 EvidenceReference 数据的不可变值对象。 */
public record EvidenceReference(String evidenceId, String evidenceType, String fundCode,
        LocalDate actualStartDate, LocalDate actualEndDate, String navBasis, String dataSource,
        String dataVersion, String algorithmVersion, Instant collectedAt,
        String documentId,String versionId,String chunkId,String documentTitle,LocalDate publishedDate,
        Integer pageStart,Integer pageEnd,String headingPath,String excerpt,String sourceUri) {
    
    /** 执行该 Agent 运行时组件中的 EvidenceReference 操作。 */
    public EvidenceReference(String evidenceId,String evidenceType,String fundCode,LocalDate actualStartDate,
            LocalDate actualEndDate,String navBasis,String dataSource,String dataVersion,String algorithmVersion,Instant collectedAt){
        this(evidenceId,evidenceType,fundCode,actualStartDate,actualEndDate,navBasis,dataSource,dataVersion,
                algorithmVersion,collectedAt,null,null,null,null,null,null,null,null,null,null);
    }
    
    /** 执行该 Agent 运行时组件中的 document 操作。 */
    public static EvidenceReference document(String evidenceId,String fundCode,String dataSource,String documentId,
            String versionId,String chunkId,String title,LocalDate publishedDate,int pageStart,int pageEnd,
            String heading,String excerpt,String sourceUri){return new EvidenceReference(evidenceId,"FUND_DOCUMENT",fundCode,
                null,null,null,dataSource,versionId,null,null,documentId,versionId,chunkId,title,publishedDate,pageStart,
                pageEnd,heading,excerpt,sourceUri);}
}
