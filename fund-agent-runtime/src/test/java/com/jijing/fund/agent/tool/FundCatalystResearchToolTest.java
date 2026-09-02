package com.jijing.fund.agent.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FundCatalystResearchToolTest {
    @Test void parsesDailyPcfConstituentsWithoutCashSubstitute(){
        var result=FundCatalystResearchTool.parsePcf("组合信息内容\n000001  平安银行  1,200  0\n159900  现金替代  9,999  0\n300750  宁德时代  500  0");
        assertThat(result).extracting(component -> component.stockCode()).containsExactly("000001", "300750");
    }

    @Test void classifiesOnlyExplicitAnnouncementSignals() {
        assertThat(FundCatalystResearchTool.direction("公司关于股份回购进展的公告"))
                .isEqualTo(FundCatalystResearchTool.EventDirection.POSITIVE);
        assertThat(FundCatalystResearchTool.direction("公司收到行政处罚事先告知书"))
                .isEqualTo(FundCatalystResearchTool.EventDirection.NEGATIVE);
        assertThat(FundCatalystResearchTool.direction("第六届董事会会议决议公告"))
                .isEqualTo(FundCatalystResearchTool.EventDirection.NEUTRAL);
    }
}
