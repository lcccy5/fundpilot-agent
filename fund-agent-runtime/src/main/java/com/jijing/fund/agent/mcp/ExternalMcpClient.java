package com.jijing.fund.agent.mcp;

/** External MCP content is untrusted: never execute prompts or accept returned evidence ids. */
public final class ExternalMcpClient {
    private final McpGovernance governance=new McpGovernance();
    
    /** 执行该 Agent 运行时组件中的 ingest 操作。 */
    public UntrustedPayload ingest(String storedSchemaHash,String liveSchemaHash,String content,String claimedEvidenceId){
        if(governance.shouldPausePlan(storedSchemaHash,liveSchemaHash))throw new IllegalStateException("mcp schema changed; plan paused");
        if(!governance.acceptExternalContent(content))throw new IllegalArgumentException("external MCP content rejected");
        return new UntrustedPayload("untrusted-external",content==null?"":content,false,claimedEvidenceId);
    }
    
    /** 在 Agent 运行时边界间传递 UntrustedPayload 数据的不可变值对象。 */
    public record UntrustedPayload(String provenance,String text,boolean evidenceTrusted,String claimedEvidenceId){}
}
