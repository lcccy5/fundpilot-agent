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

/**
 * 暴露本地 MCP 工具清单。匿名请求返回 401；已登录但不是管理员返回 403。
 * 工具清单来自进程内服务器，下游不可用时返回 500。
 */
@RestController
@RequestMapping("/api/v1/mcp")
public class McpCapabilitiesController {
    private final FundMcpServer server;

    /**
     * 绑定进程内 MCP 服务器，用于读取模式哈希和工具名。
     */
    public McpCapabilitiesController(FundMcpServer server) {
        this.server = server;
    }

    /**
     * 返回管理员可见的工具清单。角色集合为空或未包含管理员时返回 403，不泄露清单。
     */
    @GetMapping("/capabilities")
    public ResponseEntity<ApiResponse<Map<String, Object>>> capabilities(@CurrentUser AuthenticatedUser actor,
            HttpServletRequest request) {
        if (actor == null || actor.roles() == null || !actor.roles().contains(UserRole.ADMIN)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.failure("ADMIN_REQUIRED", "MCP management is ADMIN only", RequestIdFilter.get(request)));
        }
        return ResponseEntity.ok(ApiResponse.success(RequestIdFilter.get(request), Map.of(
                "schemaHash", server.schemaHash(),
                "tools", server.tools(),
                "sideEffects", "none-without-v4-approval",
                "endpointRef", "local://fund-mcp")));
    }
}
