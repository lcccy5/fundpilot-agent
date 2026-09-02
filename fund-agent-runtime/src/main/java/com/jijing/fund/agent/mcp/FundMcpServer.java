package com.jijing.fund.agent.mcp;

import com.jijing.fund.agent.planning.AgentCapabilityRegistry;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Internal read-only MCP server. Personal tools use server auth context, never a userId argument. */
public final class FundMcpServer {
    public static final Map<String,String> TOOLS=Map.of(
            "get_fund_profile","FUND_PROFILE_QUERY",
            "get_fund_nav_history","FUND_NAV_QUERY",
            "calculate_fund_metrics","FUND_METRICS_QUERY",
            "compare_fund_metrics","FUND_COMPARE",
            "search_fund_documents","DOCUMENT_SEARCH",
            "get_my_portfolio","PORTFOLIO_SNAPSHOT",
            "analyze_my_portfolio_risk","PORTFOLIO_RISK");
    private final McpGovernance governance=new McpGovernance();
    private final McpCallAuditor auditor;
    private final String schemaHash;
    
    /** 执行该 Agent 运行时组件中的 FundMcpServer 操作。 */
    public FundMcpServer(){this(McpCallAuditor.NOOP);}
    
    /** 执行该 Agent 运行时组件中的 FundMcpServer 操作。 */
    public FundMcpServer(McpCallAuditor auditor){
        this.auditor=auditor==null?McpCallAuditor.NOOP:auditor;
        this.schemaHash=hash(String.join("|",new TreeSet<>(TOOLS.keySet()))+"|capability-v1");
    }
    
    /** 执行该 Agent 运行时组件中的 schemaHash 操作。 */
    public String schemaHash(){return schemaHash;}
    
    /** 执行该 Agent 运行时组件中的 tools 操作。 */
    public Set<String> tools(){return TOOLS.keySet();}
    
    /** 执行 invoke 操作，并应用相应的 Agent 运行时状态变化。 */
    public McpCallResult invoke(String tool,Map<String,Object> arguments,AuthenticatedUser user,String storedSchemaHash){
        try{
            if(arguments!=null&&arguments.containsKey("userId"))throw new IllegalArgumentException("userId is not allowed in MCP arguments");
            if(governance.shouldPausePlan(storedSchemaHash,schemaHash))throw new IllegalStateException("mcp schema changed; plan paused");
            String capability=TOOLS.get(tool);
            if(capability==null||!AgentCapabilityRegistry.WHITELIST.contains(capability))throw new IllegalArgumentException("unknown or unlisted MCP tool");
            boolean personal=capability.startsWith("PORTFOLIO")||"WATCHLIST_READ".equals(capability);
            if(personal&&user==null)throw new IllegalArgumentException("authenticated user is required");
            if(AgentCapabilityRegistry.APPROVAL_REQUIRED.contains(capability))throw new IllegalStateException("side-effect tools require V4 approval");
            String content=String.valueOf(arguments==null?Map.of():arguments);
            if(!governance.acceptExternalContent(content))throw new IllegalArgumentException("external content rejected");
            auditor.record(null,null,capability,schemaHash,"SUCCESS",null,java.time.Instant.now());
            return new McpCallResult(tool,capability,"ev-mcp-"+tool,schemaHash);
        }catch(RuntimeException e){
            auditor.record(null,null,tool,schemaHash,"REJECTED",e.getClass().getSimpleName(),java.time.Instant.now());
            throw e;
        }
    }
    
    /** 判断 hash 对应的条件是否成立。 */
    private static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    
    /** 在 Agent 运行时边界间传递 McpCallResult 数据的不可变值对象。 */
    public record McpCallResult(String tool,String capability,String evidenceId,String schemaHash){}
}
