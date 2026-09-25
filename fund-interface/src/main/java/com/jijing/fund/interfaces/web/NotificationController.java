package com.jijing.fund.interfaces.web;

import com.jijing.fund.agent.notification.NotificationStore;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationStore store;
    public NotificationController(NotificationStore store){this.store=store;}
    @GetMapping
    public ApiResponse<List<NotificationStore.StoredNotification>> list(@CurrentUser AuthenticatedUser actor,HttpServletRequest request){
        return ApiResponse.success(RequestIdFilter.get(request),store.listOwned(actor.userId().value()));
    }
    @PostMapping("/{notificationId}/read")
    public ApiResponse<Boolean> markRead(@CurrentUser AuthenticatedUser actor,@PathVariable String notificationId,HttpServletRequest request){
        boolean updated=store.markRead(actor.userId().value(),notificationId,Instant.now());
        if(!updated)throw new com.jijing.fund.agent.exception.AgentRunNotFoundException("notification not found");
        return ApiResponse.success(RequestIdFilter.get(request),true);
    }
}
