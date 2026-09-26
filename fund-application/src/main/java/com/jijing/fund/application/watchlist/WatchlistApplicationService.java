package com.jijing.fund.application.watchlist;

import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.watchlist.WatchlistGroup;
import com.jijing.fund.domain.watchlist.WatchlistItem;
import com.jijing.fund.domain.watchlist.WatchlistRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * 维护用户自己的自选分组和基金条目。分组或条目不属于当前用户时，读取类操作抛出 {@link WatchlistNotFoundException}。
 * 版本不匹配或仓库拒绝写入时抛出 {@link WatchlistConflictException}。删除分组不先核对是否存在，仓库拒绝时一律报并发冲突。
 */
public class WatchlistApplicationService implements WatchlistUseCase {
    private final WatchlistRepository repository;
    private final Clock clock;

    /**
     * 保存仓库和时钟。二者为空时不会立刻失败，等到第一次使用才抛出空指针异常。
     */
    public WatchlistApplicationService(WatchlistRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 返回该用户的全部分组。用户为空时抛出空指针异常。
     */
    @Override
    public List<WatchlistGroup> list(AuthenticatedUser actor) {
        return repository.findByOwner(actor.userId());
    }

    /**
     * 新建分组，排序号取当前分组数量，版本从 0 开始。名称为空、全空白时抛出异常且不保存；去空白后超过 80 个字符时提示过长。
     */
    @Override
    @Transactional
    public WatchlistGroup create(AuthenticatedUser actor, String name) {
        Instant now = clock.instant();
        String display = required(name, 80);
        var group = new WatchlistGroup(UUID.randomUUID().toString(), actor.userId(), display,
                repository.findByOwner(actor.userId()).size(), 0, List.of(), now, now);
        repository.saveGroup(group);
        return group;
    }

    /**
     * 按客户端给出的版本改名，成功后重新读取分组。分组不存在或不属于该用户时抛出找不到异常。
     * 名称不合法时抛出异常且不写库。仓库认为版本已变化时抛出冲突异常。服务自身不比较分组上的版本号。
     */
    @Override
    @Transactional
    public WatchlistGroup rename(AuthenticatedUser actor, String groupId, String name, long version) {
        var group = owned(actor, groupId);
        Instant now = clock.instant();
        var next = new WatchlistGroup(group.groupId(), group.ownerUserId(), required(name, 80), group.sortOrder(), version + 1,
                group.items(), group.createdAt(), now);
        if (!repository.updateGroup(next, version)) {
            throw new WatchlistConflictException("watchlist was updated concurrently");
        }
        return owned(actor, groupId);
    }

    /**
     * 按版本删除分组。仓库返回失败时抛出冲突异常，分组不存在、不属于该用户和版本过期使用同一句提示，调用方无法从异常类型区分。
     */
    @Override
    @Transactional
    public void delete(AuthenticatedUser actor, String groupId, long version) {
        if (!repository.deleteGroup(actor.userId(), groupId, version)) {
            throw new WatchlistConflictException("watchlist was updated concurrently");
        }
    }

    /**
     * 向分组追加一只基金。分组不存在时抛出找不到异常。基金代码不是六位数字时抛出参数异常。
     * 备注去空白后超过 500 个字符时抛出异常。仓库因重复等原因抛出参数异常时，改抛冲突异常并保留原消息；其他运行时异常原样向外传播。
     */
    @Override
    @Transactional
    public WatchlistGroup add(AuthenticatedUser actor, String groupId, String fundCode, String note, List<String> tags) {
        var group = owned(actor, groupId);
        Instant now = clock.instant();
        var item = new WatchlistItem(UUID.randomUUID().toString(), new FundCode(fundCode), optional(note, 500), normalizeTags(tags),
                group.items().size(), 0, now, now);
        try {
            repository.saveItem(actor.userId(), groupId, item);
        } catch (IllegalArgumentException exception) {
            throw new WatchlistConflictException(exception.getMessage());
        }
        return owned(actor, groupId);
    }

    /**
     * 修改条目的备注和标签。分组不存在时抛出找不到异常；条目不在当前分组里时也抛出找不到异常。
     * 备注过长时抛出异常。仓库拒绝该版本时抛出冲突异常。标签超过 10 个时静默截断，不报错。
     */
    @Override
    @Transactional
    public WatchlistGroup updateItem(AuthenticatedUser actor, String groupId, String itemId, String note, List<String> tags, long version) {
        var group = owned(actor, groupId);
        var item = group.items().stream()
                .filter(candidate -> candidate.itemId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new WatchlistNotFoundException("watchlist item not found"));
        Instant now = clock.instant();
        var next = new WatchlistItem(item.itemId(), item.fundCode(), optional(note, 500), normalizeTags(tags), item.sortOrder(),
                version + 1, item.createdAt(), now);
        if (!repository.updateItem(actor.userId(), groupId, next, version)) {
            throw new WatchlistConflictException("watchlist item was updated concurrently");
        }
        return owned(actor, groupId);
    }

    /**
     * 按版本删除条目。分组不存在时抛出找不到异常。仓库拒绝时抛出冲突异常，条目不存在和版本过期无法从异常类型区分。
     */
    @Override
    @Transactional
    public WatchlistGroup removeItem(AuthenticatedUser actor, String groupId, String itemId, long version) {
        owned(actor, groupId);
        if (!repository.deleteItem(actor.userId(), groupId, itemId, version)) {
            throw new WatchlistConflictException("watchlist item was updated concurrently");
        }
        return owned(actor, groupId);
    }

    /**
     * 把本地基金代码并入已有的第一个分组；用户还没有分组时先创建名为「默认分组」的分组。
     * 代码列表为空时不新增条目。不是六位数字的代码计入拒绝，不抛异常；空代码记成空串。
     * 分组里已经有的代码计入已存在。追加时抛出的运行时异常被吞掉并计入拒绝，因此合并期间的冲突不会传给调用方。
     * 读取分组失败仍然向外抛出，不会被记成拒绝。
     */
    @Override
    @Transactional
    public MergeLocalResult mergeLocal(AuthenticatedUser actor, List<String> fundCodes) {
        var groups = repository.findByOwner(actor.userId());
        WatchlistGroup target = groups.isEmpty() ? create(actor, "默认分组") : groups.getFirst();
        int added = 0;
        int existing = 0;
        int rejected = 0;
        var rejectedCodes = new ArrayList<String>();
        for (String code : fundCodes == null ? List.<String>of() : fundCodes) {
            if (code == null || !code.matches("\\d{6}")) {
                rejected++;
                rejectedCodes.add(code == null ? "" : code);
                continue;
            }
            boolean present = owned(actor, target.groupId()).items().stream()
                    .anyMatch(item -> item.fundCode().value().equals(code));
            if (present) {
                existing++;
                continue;
            }
            try {
                add(actor, target.groupId(), code, null, List.of());
                added++;
            } catch (RuntimeException exception) {
                rejected++;
                rejectedCodes.add(code);
            }
        }
        return new MergeLocalResult(target.groupId(), added, existing, rejected, List.copyOf(rejectedCodes));
    }

    /**
     * 读取当前用户的分组。不存在或不属于该用户时抛出找不到异常。
     */
    private WatchlistGroup owned(AuthenticatedUser actor, String groupId) {
        return repository.findByIdAndOwner(groupId, actor.userId())
                .orElseThrow(() -> new WatchlistNotFoundException("watchlist not found"));
    }

    /**
     * 要求文本去空白后非空，并且不超过给定长度。空或全空白时提示名称必填；超长时提示过长。空引用也按名称必填处理。
     */
    private static String required(String value, int limit) {
        String out = optional(value, limit);
        if (out == null || out.isBlank()) {
            throw new WatchlistException("name is required");
        }
        return out;
    }

    /**
     * 去掉首尾空白。空引用返回空，不报错。去空白后超过长度限制时抛出异常。全空白且未超长时返回空串，由调用方决定是否拒绝。
     */
    private static String optional(String value, int limit) {
        if (value == null) {
            return null;
        }
        String out = value.trim();
        if (out.length() > limit) {
            throw new WatchlistException("value is too long");
        }
        return out;
    }

    /**
     * 去掉空标签和重复标签，最多保留 10 个。列表为空时返回空列表，不报错。超长、大小写不同的重复项都不会抛异常，只是被截断或保留。
     */
    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        return tags.stream().filter(Objects::nonNull).map(String::trim).filter(tag -> !tag.isBlank()).distinct().limit(10).toList();
    }
}
