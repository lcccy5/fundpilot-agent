package com.jijing.fund.agent.orchestration;
import java.util.List;
/** 在 Agent 运行时边界间传递 AnswerClaim 数据的不可变值对象。 */
public record AnswerClaim(String claimId,ClaimType type,String text,List<String> evidenceIds){public AnswerClaim{evidenceIds=evidenceIds==null?List.of():List.copyOf(evidenceIds);}}
