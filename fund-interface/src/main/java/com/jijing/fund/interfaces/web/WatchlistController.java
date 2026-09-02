package com.jijing.fund.interfaces.web;

import com.jijing.fund.application.watchlist.WatchlistUseCase;
import com.jijing.fund.application.watchlist.MergeLocalResult;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.watchlist.WatchlistGroup;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/watchlists")
public class WatchlistController {
    private final WatchlistUseCase useCase;
    public WatchlistController(WatchlistUseCase useCase){this.useCase=useCase;}
    @GetMapping public ApiResponse<List<WatchlistGroup>> list(@CurrentUser AuthenticatedUser user,HttpServletRequest request){return ApiResponse.success(RequestIdFilter.get(request),useCase.list(user));}
    @PostMapping public ApiResponse<WatchlistGroup> create(@CurrentUser AuthenticatedUser user,@Valid @RequestBody GroupBody body,HttpServletRequest request){return ApiResponse.success(RequestIdFilter.get(request),useCase.create(user,body.name()));}
    @PatchMapping("/{groupId}") public ApiResponse<WatchlistGroup> rename(@CurrentUser AuthenticatedUser user,@PathVariable("groupId") String groupId,@Valid @RequestBody GroupPatch body,HttpServletRequest request){return ApiResponse.success(RequestIdFilter.get(request),useCase.rename(user,groupId,body.name(),body.version()));}
    @DeleteMapping("/{groupId}") public ApiResponse<Void> delete(@CurrentUser AuthenticatedUser user,@PathVariable String groupId,@RequestParam long version,HttpServletRequest request){useCase.delete(user,groupId,version);return ApiResponse.success(RequestIdFilter.get(request),null);}
    @PostMapping("/{groupId}/items") public ApiResponse<WatchlistGroup> add(@CurrentUser AuthenticatedUser user,@PathVariable String groupId,@Valid @RequestBody ItemBody body,HttpServletRequest request){return ApiResponse.success(RequestIdFilter.get(request),useCase.add(user,groupId,body.fundCode(),body.note(),body.tags()));}
    @PatchMapping("/{groupId}/items/{itemId}") public ApiResponse<WatchlistGroup> updateItem(@CurrentUser AuthenticatedUser user,@PathVariable String groupId,@PathVariable String itemId,@Valid @RequestBody ItemPatch body,HttpServletRequest request){return ApiResponse.success(RequestIdFilter.get(request),useCase.updateItem(user,groupId,itemId,body.note(),body.tags(),body.version()));}
    @DeleteMapping("/{groupId}/items/{itemId}") public ApiResponse<WatchlistGroup> removeItem(@CurrentUser AuthenticatedUser user,@PathVariable String groupId,@PathVariable String itemId,@RequestParam long version,HttpServletRequest request){return ApiResponse.success(RequestIdFilter.get(request),useCase.removeItem(user,groupId,itemId,version));}
    @PostMapping("/merge-local") public ApiResponse<MergeLocalResult> merge(@CurrentUser AuthenticatedUser user,@Valid @RequestBody MergeBody body,HttpServletRequest request){return ApiResponse.success(RequestIdFilter.get(request),useCase.mergeLocal(user,body.fundCodes()));}
    public record GroupBody(@NotBlank @Size(max=80) String name){}
    public record GroupPatch(@NotBlank @Size(max=80) String name,@NotNull Long version){}
    public record ItemBody(@Pattern(regexp="\\d{6}") String fundCode,@Size(max=500) String note,@Size(max=10) List<@Size(max=32) String> tags){}
    public record ItemPatch(@Size(max=500) String note,@Size(max=10) List<@Size(max=32) String> tags,@NotNull Long version){}
    public record MergeBody(@Size(max=200) List<@Pattern(regexp="\\d{6}") String> fundCodes){}
}
