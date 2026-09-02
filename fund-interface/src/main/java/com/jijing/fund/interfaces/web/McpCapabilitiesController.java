package com.jijing.fund.interfaces.web;

import com.jijing.fund.agent.mcp.FundMcpServer;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.UserRole;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mcp")
public class McpCapabilitiesController {
    private final FundMcpServer server;
    public McpCapabilitiesController(FundMcpServer server){this.server=server;}
    @GetMapping("/capabilities")
    public ResponseEntity<ApiResponse<Map<String,Object>>> capabilities(@CurrentUser AuthenticatedUser actor,HttpServletRequest request){
        if(actor==null||actor.roles()==null||!actor.roles().contains(UserRole.ADMIN)){
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.failure("ADMIN_REQUIRED","MCP management is ADMIN only",RequestIdFilter.get(request)));
        }
        return ResponseEntity.ok(ApiResponse.success(RequestIdFilter.get(request),Map.of(
                "schemaHash",server.schemaHash(),
                "tools",server.tools(),
                "sideEffects","none-without-v4-approval",
                "endpointRef","local://fund-mcp")));
    }
}
