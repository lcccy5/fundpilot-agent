package com.jijing.fund.agent.orchestration;

import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.exception.AgentEvidenceViolationException;
import java.util.*;
import java.util.regex.*;

/** 实现 FundAgentCitationPolicy 所代表的 Agent 运行时职责。 */
public final class FundAgentCitationPolicy {
    private static final Pattern CITATION=Pattern.compile("(?:DOC:[A-Za-z0-9._:-]+|ev-[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)");
    private static final Pattern NUMERIC=Pattern.compile("(?<![A-Za-z])(?:\\d{6}|[-+]?\\d+(?:\\.\\d+)?%)");
    private static final Set<String> DOCUMENT_EVIDENCE=Set.of("FUND_DOCUMENT","COMPANY_ANNOUNCEMENT","GOVERNMENT_POLICY","VERIFIED_NEWS");
    
    /** 在继续处理前校验 validateAndRepair 对应的输入或状态。 */
    public String validateAndRepair(String answer,List<EvidenceReference>evidence){Set<String>allowed=evidence.stream().map(EvidenceReference::evidenceId).collect(java.util.stream.Collectors.toSet());
        if(allowed.isEmpty()){Matcher matcher=CITATION.matcher(answer);if(matcher.find())throw new AgentEvidenceViolationException("模型生成了本次运行不存在的证据引用");return answer;}
        String sanitized=removeInvalidCitations(answer,allowed);
        List<AnswerClaim>claims=claims(sanitized);List<String>missing=new ArrayList<>();for(AnswerClaim claim:claims){if(requiresEvidence(claim.type())&&!hasCompatibleEvidence(claim,evidence))missing.add(claim.claimId());}
        if(missing.isEmpty())return sanitized;StringBuilder repaired=new StringBuilder();for(AnswerClaim claim:claims){if(requiresEvidence(claim.type())&&!hasCompatibleEvidence(claim,evidence)){EvidenceReference selected=select(claim,evidence);if(selected==null)throw new AgentEvidenceViolationException("Claim 缺少正确类型的可用证据");repaired.append(withCitation(claim.text(),format(selected)));}else repaired.append(claim.text());}List<AnswerClaim>repairedClaims=claims(repaired.toString());for(AnswerClaim claim:repairedClaims){if(requiresEvidence(claim.type())&&!hasCompatibleEvidence(claim,evidence))throw new AgentEvidenceViolationException("一次修复后 Claim 仍缺少正确类型证据");}return repaired.toString();}
    
    /** 执行 removeInvalidCitations 对应的资源状态转换。 */
    private String removeInvalidCitations(String answer,Set<String>allowed){Matcher matcher=CITATION.matcher(answer);StringBuffer sanitized=new StringBuffer();while(matcher.find())matcher.appendReplacement(sanitized,allowed.contains(matcher.group())?Matcher.quoteReplacement(matcher.group()):"");matcher.appendTail(sanitized);return sanitized.toString().replaceAll("【\\s*】","").replaceAll("[ \\t]{2,}"," ");}
    
    /** 通过 claims 操作更新持久化或内存中的运行状态。 */
    public List<AnswerClaim> claims(String answer){String[]sentences=answer.split("(?<=[。！？!?\\n])",-1);List<AnswerClaim>result=new ArrayList<>();int i=1;for(String text:sentences){if(text.isEmpty())continue;Matcher m=CITATION.matcher(text);List<String>ids=new ArrayList<>();while(m.find())ids.add(m.group());result.add(new AnswerClaim("claim-"+i++,type(text),text,ids));}return List.copyOf(result);}
    
    /** 执行该 Agent 运行时组件中的 type 操作。 */
    private ClaimType type(String text){if(text.contains("历史表现不代表")||text.contains("仅供参考")||text.contains("不构成投资建议"))return ClaimType.LIMITATION;if(text.contains("公告")||text.contains("招募说明书")||text.contains("季度报告")||text.contains("半年度报告")||text.contains("年度报告")||text.contains("定期报告")||text.contains("投资策略")||text.contains("投资范围")||text.contains("业绩比较基准")||text.contains("基金经理观点")||text.contains("基金经理强调")||text.contains("基金经理表示")||text.contains("原文")||text.contains("条款")||text.contains("利好")||text.contains("利空")||text.contains("事件"))return ClaimType.DOCUMENT_FACT;if(NUMERIC.matcher(text).find())return ClaimType.NUMERIC_FACT;if(text.contains("可能")||text.contains("表明")||text.contains("意味着")||text.contains("基于"))return ClaimType.INTERPRETATION;return ClaimType.GENERAL_EDUCATION;}
    
    /** 执行该 Agent 运行时组件中的 requiresEvidence 操作。 */
    private boolean requiresEvidence(ClaimType type){return type==ClaimType.NUMERIC_FACT||type==ClaimType.DOCUMENT_FACT||type==ClaimType.INTERPRETATION;}
    
    /** 执行该 Agent 运行时组件中的 select 操作。 */
    private EvidenceReference select(AnswerClaim claim,List<EvidenceReference>evidence){if(claim.type()==ClaimType.DOCUMENT_FACT){var document=evidence.stream().filter(e->DOCUMENT_EVIDENCE.contains(e.evidenceType())).max(Comparator.comparingInt(e->documentMatchScore(claim.text(),e)));if(document.isPresent())return document.get();if(isManagerProfileFact(claim.text()))return evidence.stream().filter(e->"FUND_PROFILE".equals(e.evidenceType())).findFirst().orElse(null);return null;}if(claim.type()==ClaimType.NUMERIC_FACT)return evidence.stream().filter(e->!DOCUMENT_EVIDENCE.contains(e.evidenceType())).findFirst().orElse(null);return evidence.stream().findFirst().orElse(null);}
    
    /** 执行该 Agent 运行时组件中的 withCitation 操作。 */
    private String withCitation(String text,String citation){if(!text.isEmpty()&&"。！？!?\n".indexOf(text.charAt(text.length()-1))>=0)return text.substring(0,text.length()-1)+" "+citation+text.charAt(text.length()-1);return text+" "+citation;}
    
    /** 判断 hasCompatibleEvidence 对应的条件是否成立。 */
    private boolean hasCompatibleEvidence(AnswerClaim claim,List<EvidenceReference>evidence){Map<String,EvidenceReference>byId=evidence.stream().collect(java.util.stream.Collectors.toMap(EvidenceReference::evidenceId,e->e,(a,b)->a));for(String id:claim.evidenceIds()){EvidenceReference ref=byId.get(id);if(ref==null)continue;if(claim.type()==ClaimType.DOCUMENT_FACT&&(DOCUMENT_EVIDENCE.contains(ref.evidenceType())||(isManagerProfileFact(claim.text())&&"FUND_PROFILE".equals(ref.evidenceType()))))return true;if(claim.type()==ClaimType.NUMERIC_FACT&&!DOCUMENT_EVIDENCE.contains(ref.evidenceType()))return true;if(claim.type()==ClaimType.INTERPRETATION)return true;}return false;}
    
    /** 判断 isManagerProfileFact 对应的条件是否成立。 */
    private boolean isManagerProfileFact(String text){return text.contains("基金经理")&&!text.contains("观点")&&!text.contains("表示")&&!text.contains("原文")&&!text.contains("报告");}
    
    /** 执行该 Agent 运行时组件中的 documentMatchScore 操作。 */
    private int documentMatchScore(String claim,EvidenceReference evidence){String title=evidence.documentTitle();if(title==null||title.isBlank())return 0;if(claim.contains(title))return 1000;int score=0;for(String token:title.split("[，。：:（）()\\s]+")){if(token.length()>=2&&claim.contains(token))score+=token.length();}for(String keyword:List.of("回购","增持","减持","中标","签订","处罚","诉讼","立案","半年度报告","业绩说明会")){if(claim.contains(keyword)&&title.contains(keyword))score+=50;}return score;}
    
    /** 执行该 Agent 运行时组件中的 format 操作。 */
    private String format(EvidenceReference evidence){if("FUND_DOCUMENT".equals(evidence.evidenceType())&&evidence.pageStart()!=null)return "【"+evidence.evidenceId()+"，第"+(Objects.equals(evidence.pageStart(),evidence.pageEnd())?evidence.pageStart():evidence.pageStart()+"-"+evidence.pageEnd())+"页】";return evidence.evidenceId();}
}
