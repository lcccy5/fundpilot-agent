package com.jijing.fund.application.risk;

import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.risk.RiskProfile;
import java.util.List;
import java.util.Map;

public interface RiskProfileUseCase {
    Questionnaire questionnaire();
    RiskProfile current(AuthenticatedUser actor);
    RiskProfile submit(AuthenticatedUser actor,String questionnaireVersion,Map<String,Integer> answers);
    record Questionnaire(String version,List<Question> questions){}
    record Question(String id,String prompt,List<Choice> choices){}
    record Choice(int value,String label){}
}
