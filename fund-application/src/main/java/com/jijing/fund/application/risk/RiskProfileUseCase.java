package com.jijing.fund.application.risk;

import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.risk.RiskProfile;
import java.util.List;
import java.util.Map;

/**
 * 风险问卷的应用边界。等级必须由服务端根据答案计算，不能信任调用方自行声明的等级。
 * 用户之间的档案必须隔离。
 */
public interface RiskProfileUseCase {
    /**
     * 返回当前生效的问卷。没有输入。
     */
    Questionnaire questionnaire();

    /**
     * 读取当前用户最近一次档案。还没有提交过时失败，不能返回其他用户的结果。
     */
    RiskProfile current(AuthenticatedUser actor);

    /**
     * 提交一份答案并得到新的档案。版本不受支持、答案缺失或分值超出选项时失败，且不能留下半份档案。
     */
    RiskProfile submit(AuthenticatedUser actor, String questionnaireVersion, Map<String, Integer> answers);

    /**
     * 一份可展示的问卷。version 用来在提交时核对；questions 为空时提交必然失败。记录不校验版本是否仍受支持。
     */
    record Questionnaire(String version, List<Question> questions) {
    }

    /**
     * 一道单选题。id 是提交答案时使用的键。选项列表为空或分值重复时，记录本身不拒绝，提交阶段才会因答案非法失败。
     */
    record Question(String id, String prompt, List<Choice> choices) {
    }

    /**
     * 一个选项。value 是计入总分的分值，label 是展示文案。不限制分值范围，范围检查发生在提交时。
     */
    record Choice(int value, String label) {
    }
}
