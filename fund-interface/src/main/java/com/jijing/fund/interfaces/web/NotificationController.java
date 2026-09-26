package com.jijing.fund.interfaces.web;

import com.jijing.fund.agent.notification.NotificationStore;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户的站内通知列表。只按属主过滤，因此不存在跨用户读取后的 404。
 * 匿名请求返回 401。存储访问失败时返回 500。
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationStore store;

    /**
     * 绑定按属主查询通知的存储。
     */
    public NotificationController(NotificationStore store) {
        this.store = store;
    }

    /**
     * 返回当前用户名下的通知。没有登录主体时返回 401。
     */
    @GetMapping
    public ApiResponse<List<NotificationStore.StoredNotification>> list(@CurrentUser AuthenticatedUser actor,
            HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), store.listOwned(actor.userId().value()));
    }
}
