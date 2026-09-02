package com.jijing.fund.interfaces.web;

import com.jijing.fund.agent.api.*;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import com.jijing.fund.domain.identity.AuthenticatedUser;

@RestController
@RequestMapping("/api/v1/agent")
public class FundAgentController {
    private final FundAgentUseCase useCase;
    private final AgentRunUseCase runs;
    public FundAgentController(FundAgentUseCase useCase,AgentRunUseCase runs){this.useCase=useCase;this.runs=runs;}

    @PostMapping("/conversations")
    public ApiResponse<ConversationResult> create(@CurrentUser AuthenticatedUser actor,HttpServletRequest request){return ApiResponse.success(RequestIdFilter.get(request),useCase.createConversation(actor));}

    @PostMapping("/chat")
    public ApiResponse<FundAgentResponse> chat(@CurrentUser AuthenticatedUser actor,@Valid @RequestBody ChatRequest body,HttpServletRequest request){
        return ApiResponse.success(RequestIdFilter.get(request),useCase.chat(new FundAgentRequest(body.conversationId(),body.message(),RequestIdFilter.get(request),actor)));
    }

    @PostMapping(value="/chat/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<FundAgentEvent>> stream(@CurrentUser AuthenticatedUser actor,@Valid @RequestBody ChatRequest body,HttpServletRequest request){
        return useCase.stream(new FundAgentRequest(body.conversationId(),body.message(),RequestIdFilter.get(request),actor))
                .map(event->{var builder=ServerSentEvent.builder(event).event(event.type());if(event.runId()!=null)builder.id(event.runId());return builder.build();});
    }

    @PostMapping("/runs")
    public ApiResponse<AgentRunView> submitRun(@CurrentUser AuthenticatedUser actor,@Valid @RequestBody RunRequest body,HttpServletRequest request){
        return ApiResponse.success(RequestIdFilter.get(request),runs.submit(new AgentRunCommand(body.conversationId(),body.message(),RequestIdFilter.get(request),actor.userId().value(),true)));
    }
    @GetMapping("/runs/{runId}")
    public ApiResponse<AgentRunView> getRun(@CurrentUser AuthenticatedUser actor,@PathVariable("runId") String runId,HttpServletRequest request){
        return ApiResponse.success(RequestIdFilter.get(request),runs.get(runId,actor.userId().value()));
    }
    @GetMapping("/runs/{runId}/plan")
    public ApiResponse<AgentPlanView> getPlan(@CurrentUser AuthenticatedUser actor,@PathVariable("runId") String runId,HttpServletRequest request){
        return ApiResponse.success(RequestIdFilter.get(request),runs.plan(runId,actor.userId().value()));
    }
    @GetMapping("/runs/{runId}/events")
    public ApiResponse<java.util.List<AgentRunEventView>> getEvents(@CurrentUser AuthenticatedUser actor,@PathVariable("runId") String runId,@RequestHeader(name="Last-Event-ID",required=false) String lastEventId,@RequestParam(name="after",required=false) Long after,HttpServletRequest request){
        Long cursor=after;if(cursor==null&&lastEventId!=null)try{cursor=Long.parseLong(lastEventId);}catch(NumberFormatException ignored){cursor=0L;}
        return ApiResponse.success(RequestIdFilter.get(request),runs.events(runId,actor.userId().value(),cursor));
    }
    @GetMapping(value="/runs/{runId}/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentRunEventView>> streamRun(@CurrentUser AuthenticatedUser actor,@PathVariable("runId") String runId,@RequestHeader(name="Last-Event-ID",required=false) String lastEventId){
        long cursor=0L;if(lastEventId!=null)try{cursor=Long.parseLong(lastEventId);}catch(NumberFormatException ignored){cursor=0L;}
        return Flux.fromIterable(runs.events(runId,actor.userId().value(),cursor))
                .map(event->ServerSentEvent.builder(event).id(String.valueOf(event.sequence())).event(event.eventType()).build());
    }
    @PostMapping("/runs/{runId}/cancel")
    public ApiResponse<Void> cancel(@CurrentUser AuthenticatedUser actor,@PathVariable("runId") String runId,HttpServletRequest request){runs.cancel(runId,actor.userId().value());return ApiResponse.success(RequestIdFilter.get(request),null);}
    @PostMapping({"/runs/{runId}/pause","/runs/{runId}/resume"})
    public ResponseEntity<ApiResponse<Void>> pauseOrResumeUnsupported(@PathVariable("runId") String runId,HttpServletRequest request){
        return ResponseEntity.status(HttpStatus.GONE)
                .body(ApiResponse.failure("CANCEL_ONLY","pause/resume is not supported; cancel the run",RequestIdFilter.get(request)));
    }
    @PostMapping("/runs/{runId}/approvals/{approvalId}")
    public ApiResponse<Void> approve(@CurrentUser AuthenticatedUser actor,@PathVariable("runId") String runId,@PathVariable("approvalId") String approvalId,@RequestBody(required=false) ApprovalBody body,HttpServletRequest request){
        runs.approve(runId,approvalId,actor.userId().value(),body==null?"":body.parameters());return ApiResponse.success(RequestIdFilter.get(request),null);
    }
    @PostMapping("/runs/{runId}/approvals/{approvalId}/reject")
    public ApiResponse<Void> reject(@CurrentUser AuthenticatedUser actor,@PathVariable("runId") String runId,@PathVariable("approvalId") String approvalId,HttpServletRequest request){
        runs.reject(runId,approvalId,actor.userId().value());return ApiResponse.success(RequestIdFilter.get(request),null);
    }

    public record ChatRequest(@NotBlank String conversationId,@NotBlank @Size(max=2000) String message){}
    public record RunRequest(String conversationId,@NotBlank @Size(max=2000) String message){}
    public record ApprovalBody(String parameters){}
}
