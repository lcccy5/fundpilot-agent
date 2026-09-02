package com.jijing.fund.agent.mcp;

import java.util.Objects;

/** 实现 McpGovernance 所代表的 Agent 运行时职责。 */
public final class McpGovernance {
    
    /** 判断 shouldPausePlan 对应的条件是否成立。 */
    public boolean shouldPausePlan(String storedSchemaHash,String liveSchemaHash){
        return storedSchemaHash==null||liveSchemaHash==null||!Objects.equals(storedSchemaHash,liveSchemaHash);
    }
    
    /** 执行该 Agent 运行时组件中的 acceptExternalContent 操作。 */
    public boolean acceptExternalContent(String content){
        if(content==null)return false;
        String lower=content.toLowerCase();
        return !(lower.contains("ignore previous")||lower.contains("system prompt")||lower.contains("evidence-id:forged"));
    }
}
