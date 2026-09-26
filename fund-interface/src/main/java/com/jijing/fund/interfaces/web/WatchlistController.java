package com.jijing.fund.interfaces.web;

import com.jijing.fund.application.watchlist.MergeLocalResult;
import com.jijing.fund.application.watchlist.WatchlistUseCase;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.watchlist.WatchlistGroup;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户的自选分组。所有路由都要求登录主体，匿名请求返回 401。
 * 名称、基金代码或版本号不合法，以及删除时缺少版本参数，返回 400。
 * 分组或条目不属于当前用户时返回 404。版本冲突返回 409。未分类异常返回 500。
 */
@RestController
@RequestMapping("/api/v1/watchlists")
public class WatchlistController {
    private final WatchlistUseCase useCase;

    /**
     * 绑定按当前用户隔离的自选用例。
     */
    public WatchlistController(WatchlistUseCase useCase) {
        this.useCase = useCase;
    }

    /**
     * 列出当前用户的全部分组。未登录返回 401。
     */
    @GetMapping
    public ApiResponse<List<WatchlistGroup>> list(@CurrentUser AuthenticatedUser user, HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), useCase.list(user));
    }

    /**
     * 新建分组。名称为空或超长返回 400。
     */
    @PostMapping
    public ApiResponse<WatchlistGroup> create(@CurrentUser AuthenticatedUser user, @Valid @RequestBody GroupBody body,
            HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), useCase.create(user, body.name()));
    }

    /**
     * 按版本重命名分组。分组不存在或不属于当前用户返回 404，版本过期返回 409。
     */
    @PatchMapping("/{groupId}")
    public ApiResponse<WatchlistGroup> rename(@CurrentUser AuthenticatedUser user,
            @PathVariable("groupId") String groupId, @Valid @RequestBody GroupPatch body, HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), useCase.rename(user, groupId, body.name(), body.version()));
    }

    /**
     * 按版本删除分组。缺少 version 返回 400，并发修改返回 409，分组不存在返回 404。
     */
    @DeleteMapping("/{groupId}")
    public ApiResponse<Void> delete(@CurrentUser AuthenticatedUser user, @PathVariable("groupId") String groupId,
            @RequestParam("version") long version, HttpServletRequest request) {
        useCase.delete(user, groupId, version);
        return ApiResponse.success(RequestIdFilter.get(request), null);
    }

    /**
     * 向分组加入基金。代码不是六位数字返回 400，分组不存在返回 404。
     */
    @PostMapping("/{groupId}/items")
    public ApiResponse<WatchlistGroup> add(@CurrentUser AuthenticatedUser user, @PathVariable("groupId") String groupId,
            @Valid @RequestBody ItemBody body, HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request),
                useCase.add(user, groupId, body.fundCode(), body.note(), body.tags()));
    }

    /**
     * 修改条目备注和标签。版本缺失返回 400，条目不存在返回 404，版本冲突返回 409。
     */
    @PatchMapping("/{groupId}/items/{itemId}")
    public ApiResponse<WatchlistGroup> updateItem(@CurrentUser AuthenticatedUser user,
            @PathVariable("groupId") String groupId, @PathVariable("itemId") String itemId,
            @Valid @RequestBody ItemPatch body, HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request),
                useCase.updateItem(user, groupId, itemId, body.note(), body.tags(), body.version()));
    }

    /**
     * 按版本移除条目。缺少 version 返回 400，条目不存在返回 404。
     */
    @DeleteMapping("/{groupId}/items/{itemId}")
    public ApiResponse<WatchlistGroup> removeItem(@CurrentUser AuthenticatedUser user,
            @PathVariable("groupId") String groupId, @PathVariable("itemId") String itemId,
            @RequestParam("version") long version, HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), useCase.removeItem(user, groupId, itemId, version));
    }

    /**
     * 把浏览器本地的基金代码合并进服务端自选。代码格式非法或超过 200 只返回 400。
     */
    @PostMapping("/merge-local")
    public ApiResponse<MergeLocalResult> merge(@CurrentUser AuthenticatedUser user, @Valid @RequestBody MergeBody body,
            HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), useCase.mergeLocal(user, body.fundCodes()));
    }

    /**
     * 新建分组时的名称。
     */
    public record GroupBody(@NotBlank @Size(max = 80) String name) {}

    /**
     * 重命名时同时携带期望版本，避免覆盖他人或旧客户端的修改。
     */
    public record GroupPatch(@NotBlank @Size(max = 80) String name, @NotNull Long version) {}

    /**
     * 加入自选的基金。代码须为六位数字，标签最多 10 个。
     */
    public record ItemBody(@Pattern(regexp = "\\d{6}") String fundCode, @Size(max = 500) String note,
            @Size(max = 10) List<@Size(max = 32) String> tags) {}

    /**
     * 更新条目。版本必填，备注和标签可空。
     */
    public record ItemPatch(@Size(max = 500) String note, @Size(max = 10) List<@Size(max = 32) String> tags,
            @NotNull Long version) {}

    /**
     * 本地合并的基金代码列表，单次最多 200 个六位代码。
     */
    public record MergeBody(@Size(max = 200) List<@Pattern(regexp = "\\d{6}") String> fundCodes) {}
}
