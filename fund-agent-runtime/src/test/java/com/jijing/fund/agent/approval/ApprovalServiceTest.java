package com.jijing.fund.agent.approval;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ApprovalServiceTest {
    @Test void parameterChangeInvalidatesApproval(){
        var svc=new ApprovalService();
        String hash=svc.hash("export:v1");
        Instant now=Instant.parse("2026-08-27T08:00:00Z");
        assertThat(svc.isValid(hash,"export:v1",now.plusSeconds(60),now,null)).isTrue();
        assertThat(svc.isValid(hash,"export:v2",now.plusSeconds(60),now,null)).isFalse();
        assertThat(svc.isValid(hash,"export:v1",now.plusSeconds(60),now,now)).isFalse();
    }
}
