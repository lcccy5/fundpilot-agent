package com.jijing.fund.interfaces.web;

import com.jijing.fund.agent.api.AgentPlanView;
import com.jijing.fund.agent.api.AgentRunCommand;
import com.jijing.fund.agent.api.AgentRunEventView;
import com.jijing.fund.agent.api.AgentRunUseCase;
import com.jijing.fund.agent.api.AgentRunView;
import com.jijing.fund.agent.api.AgentTaskView;
import com.jijing.fund.agent.api.ConversationResult;
import com.jijing.fund.agent.api.FundAgentEvent;
import com.jijing.fund.agent.api.FundAgentRequest;
import com.jijing.fund.agent.api.FundAgentResponse;
import com.jijing.fund.agent.api.FundAgentUseCase;
import com.jijing.fund.agent.verification.ReportWriter;
import com.jijing.fund.agent.verification.VerificationReport;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 面向当前用户的基金 Agent 对话与异步运行。
 * 除暂停/恢复外，路由都要求已解析的登录主体，匿名请求返回 401。
 * 消息为空或计划不合法返回 400；运行不存在或不属于当前用户返回 404。
 * Agent 关闭或模型不可用返回 503；执行额度、策略或证据违规返回 422。
 * 报告尚未写完时返回 500。暂停和恢复固定返回 410，且方法本身不检查登录态。
 */
@RestController
@RequestMapping("/api/v1/agent")
public class FundAgentController {
    private final FundAgentUseCase useCase;
    private final AgentRunUseCase runs;

    /**
     * 绑定同步对话用例和可查询、可取消的运行用例。
     */
    public FundAgentController(FundAgentUseCase useCase, AgentRunUseCase runs) {
        this.useCase = useCase;
        this.runs = runs;
    }

    /**
     * 为当前用户创建空对话。Agent 未启用时返回 503。
     */
    @PostMapping("/conversations")
    public ApiResponse<ConversationResult> create(@CurrentUser AuthenticatedUser actor, HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), useCase.createConversation(actor));
    }

    /**
     * 在已有对话中同步问答。消息空白返回 400，模型不可用返回 503，运行约束被打破返回 422。
     */
    @PostMapping("/chat")
    public ApiResponse<FundAgentResponse> chat(@CurrentUser AuthenticatedUser actor, @Valid @RequestBody ChatRequest body,
            HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request),
                useCase.chat(new FundAgentRequest(body.conversationId(), body.message(), RequestIdFilter.get(request), actor)));
    }

    /**
     * 以 Server-Sent Events 推送同一轮对话。订阅建立前失败与同步问答使用同一套状态码。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<FundAgentEvent>> stream(@CurrentUser AuthenticatedUser actor,
            @Valid @RequestBody ChatRequest body, HttpServletRequest request) {
        return useCase.stream(new FundAgentRequest(body.conversationId(), body.message(), RequestIdFilter.get(request), actor))
                .map(event -> {
                    var builder = ServerSentEvent.builder(event).event(event.type());
                    if (event.runId() != null) {
                        builder.id(event.runId());
                    }
                    return builder.build();
                });
    }

    /**
     * 提交一次可异步跟踪的运行。计划校验失败返回 400，模型不可用返回 503。
     */
    @PostMapping("/runs")
    public ApiResponse<AgentRunView> submitRun(@CurrentUser AuthenticatedUser actor, @Valid @RequestBody RunRequest body,
            HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request),
                runs.submit(new AgentRunCommand(body.conversationId(), body.message(), RequestIdFilter.get(request),
                        actor.userId().value(), true)));
    }

    /**
     * 读取当前用户拥有的运行。不存在或属主不匹配返回 404。
     */
    @GetMapping("/runs/{runId}")
    public ApiResponse<AgentRunView> getRun(@CurrentUser AuthenticatedUser actor, @PathVariable("runId") String runId,
            HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), runs.get(runId, actor.userId().value()));
    }

    /**
     * 读取运行的计划与任务。运行不存在返回 404。
     */
    @GetMapping("/runs/{runId}/plan")
    public ApiResponse<AgentPlanView> getPlan(@CurrentUser AuthenticatedUser actor, @PathVariable("runId") String runId,
            HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), runs.plan(runId, actor.userId().value()));
    }

    /**
     * 仅在属主的报告写作任务已经成功后返回可读正文。
     * 运行不存在返回 404；任务尚未成功时抛出未就绪异常并映射为 500。
     */
    @GetMapping("/runs/{runId}/report")
    public ApiResponse<ResearchReport> getReport(@CurrentUser AuthenticatedUser actor, @PathVariable("runId") String runId,
            HttpServletRequest request) {
        var plan = runs.plan(runId, actor.userId().value());
        var writer = plan.tasks().stream()
                .filter(task -> "REPORT_WRITE".equals(task.capabilityType())
                        && "SUCCEEDED".equals(task.status())
                        && task.outputUri() != null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("research report is not ready"));
        var evidence = plan.tasks().stream()
                .filter(task -> "SUCCEEDED".equals(task.status()) && task.outputUri() != null)
                .map(AgentTaskView::outputUri)
                .toList();
        String content = new ReportWriter().write(new VerificationReport(true, List.of(), evidence), plan.tasks());
        return ApiResponse.success(RequestIdFilter.get(request),
                new ResearchReport(writer.outputUri(), content, evidence.size()));
    }

    /**
     * 按游标返回运行事件。{@code after} 优先于 {@code Last-Event-ID}；后者不是数字时从 0 开始，不返回 400。
     * 运行不存在返回 404。
     */
    @GetMapping("/runs/{runId}/events")
    public ApiResponse<List<AgentRunEventView>> getEvents(@CurrentUser AuthenticatedUser actor,
            @PathVariable("runId") String runId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(name = "after", required = false) Long after, HttpServletRequest request) {
        Long cursor = after;
        if (cursor == null && lastEventId != null) {
            try {
                cursor = Long.parseLong(lastEventId);
            } catch (NumberFormatException ignored) {
                cursor = 0L;
            }
        }
        return ApiResponse.success(RequestIdFilter.get(request), runs.events(runId, actor.userId().value(), cursor));
    }

    /**
     * 把当前已持久化的事件以 Server-Sent Events 再放一次。非法的 Last-Event-ID 从 0 开始。
     * 运行不存在时在订阅建立前返回 404。
     */
    @GetMapping(value = "/runs/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentRunEventView>> streamRun(@CurrentUser AuthenticatedUser actor,
            @PathVariable("runId") String runId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        long cursor = 0L;
        if (lastEventId != null) {
            try {
                cursor = Long.parseLong(lastEventId);
            } catch (NumberFormatException ignored) {
                cursor = 0L;
            }
        }
        return Flux.fromIterable(runs.events(runId, actor.userId().value(), cursor))
                .map(event -> ServerSentEvent.builder(event).id(String.valueOf(event.sequence())).event(event.eventType()).build());
    }

    /**
     * 取消当前用户的运行。运行不存在返回 404。
     */
    @PostMapping("/runs/{runId}/cancel")
    public ApiResponse<Void> cancel(@CurrentUser AuthenticatedUser actor, @PathVariable("runId") String runId,
            HttpServletRequest request) {
        runs.cancel(runId, actor.userId().value());
        return ApiResponse.success(RequestIdFilter.get(request), null);
    }

    /**
     * 暂停和恢复已下线。无论运行是否存在，都返回 410。本方法不解析当前用户。
     */
    @PostMapping({"/runs/{runId}/pause", "/runs/{runId}/resume"})
    public ResponseEntity<ApiResponse<Void>> pauseOrResumeUnsupported(@PathVariable("runId") String runId,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(ApiResponse.failure("CANCEL_ONLY", "pause/resume is not supported; cancel the run",
                        RequestIdFilter.get(request)));
    }

    /**
     * 通过一次人工审批并附带可选参数。运行或审批不存在返回 404，参数不合法返回 400。
     */
    @PostMapping("/runs/{runId}/approvals/{approvalId}")
    public ApiResponse<Void> approve(@CurrentUser AuthenticatedUser actor, @PathVariable("runId") String runId,
            @PathVariable("approvalId") String approvalId, @RequestBody(required = false) ApprovalBody body,
            HttpServletRequest request) {
        runs.approve(runId, approvalId, actor.userId().value(), body == null ? "" : body.parameters());
        return ApiResponse.success(RequestIdFilter.get(request), null);
    }

    /**
     * 拒绝一次人工审批。运行或审批不存在返回 404，状态不允许拒绝时返回 422。
     */
    @PostMapping("/runs/{runId}/approvals/{approvalId}/reject")
    public ApiResponse<Void> reject(@CurrentUser AuthenticatedUser actor, @PathVariable("runId") String runId,
            @PathVariable("approvalId") String approvalId, HttpServletRequest request) {
        runs.reject(runId, approvalId, actor.userId().value());
        return ApiResponse.success(RequestIdFilter.get(request), null);
    }

    /**
     * 同步或流式对话的请求体。对话标识和消息都不能为空白，消息最长 2000 字。
     */
    public record ChatRequest(@NotBlank String conversationId, @NotBlank @Size(max = 2000) String message) {}

    /**
     * 异步运行请求。对话标识可空，消息规则与同步对话相同。
     */
    public record RunRequest(String conversationId, @NotBlank @Size(max = 2000) String message) {}

    /**
     * 审批附带参数。请求体可整体省略，此时按空参数继续。
     */
    public record ApprovalBody(String parameters) {}

    /**
     * 在接口边界完成属主校验后组装的报告正文，避免调用方直接读取任务输出。
     */
    public record ResearchReport(String artifactUri, String content, int evidenceCount) {}
}
