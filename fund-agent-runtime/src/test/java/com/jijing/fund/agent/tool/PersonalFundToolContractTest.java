package com.jijing.fund.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

class PersonalFundToolContractTest {
    @Test void personalToolsDoNotAcceptUserIdParameter(){
        for(Method method:PersonalFundTool.class.getDeclaredMethods()){
            if(method.getAnnotation(Tool.class)==null)continue;
            for(var param:method.getParameters())assertThat(param.getName()).isNotEqualTo("userId");
            assertThat(method.getGenericParameterTypes()).noneMatch(t->t.getTypeName().contains("userId"));
        }
    }
}
