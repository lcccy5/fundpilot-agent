package com.jijing.fund.application.risk;

import com.jijing.fund.domain.identity.*;
import com.jijing.fund.domain.risk.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

public class RiskProfileApplicationService implements RiskProfileUseCase {
    public static final String VERSION="risk-questionnaire-v1";
    private static final List<Question> QUESTIONS=List.of(
            q("horizon","投资期限",List.of(c(1,"1 年以内"),c(2,"1～3 年"),c(3,"3～5 年"),c(4,"5～10 年"),c(5,"10 年以上"))),
            q("income","收入稳定性",List.of(c(1,"很不稳定"),c(2,"不太稳定"),c(3,"一般"),c(4,"比较稳定"),c(5,"非常稳定"))),
            q("drawdown","可承受最大回撤",List.of(c(1,"5% 以内"),c(2,"10% 以内"),c(3,"20% 以内"),c(4,"30% 以内"),c(5,"超过 30%"))),
            q("liquidity","流动性需求",List.of(c(1,"随时可能用钱"),c(2,"一年内可能用钱"),c(3,"两到三年"),c(4,"五年内"),c(5,"长期不用"))),
            q("experience","投资经验",List.of(c(1,"几乎没有"),c(2,"少于 1 年"),c(3,"1～3 年"),c(4,"3～5 年"),c(5,"5 年以上"))),
            q("lossAttitude","目标收益与损失态度",List.of(c(1,"保本优先"),c(2,"宁可少赚也要少亏"),c(3,"平衡"),c(4,"可接受波动换收益"),c(5,"追求高收益并接受大幅回撤")))
    );
    private final RiskProfileRepository repository;private final Clock clock;
    public RiskProfileApplicationService(RiskProfileRepository repository,Clock clock){this.repository=repository;this.clock=clock;}
    @Override public Questionnaire questionnaire(){return new Questionnaire(VERSION,QUESTIONS);}
    @Override public RiskProfile current(AuthenticatedUser actor){return repository.findLatestByOwner(actor.userId()).orElseThrow(()->new RiskProfileException("risk profile not found"));}
    @Override @Transactional public RiskProfile submit(AuthenticatedUser actor,String questionnaireVersion,Map<String,Integer> answers){
        if(!VERSION.equals(questionnaireVersion))throw new RiskProfileException("unsupported questionnaire version");
        if(answers==null)throw new RiskProfileException("answers are required");
        int score=0;var canonical=new TreeMap<String,Integer>();
        for(Question question:QUESTIONS){
            Integer value=answers.get(question.id());
            if(value==null||value<1||value>5)throw new RiskProfileException("answer required: "+question.id());
            canonical.put(question.id(),value);score+=value;
        }
        RiskLevel level=score<=12?RiskLevel.CONSERVATIVE:score<=18?RiskLevel.BALANCED:score<=24?RiskLevel.GROWTH:RiskLevel.AGGRESSIVE;
        Instant now=clock.instant();
        var profile=new RiskProfile(UUID.randomUUID().toString(),actor.userId(),VERSION,sha(canonical.toString()),score,level,now,now);
        repository.save(profile);return profile;
    }
    private static Question q(String id,String prompt,List<Choice> choices){return new Question(id,prompt,choices);}
    private static Choice c(int value,String label){return new Choice(value,label);}
    private static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
