package com.jijing.fund.infrastructure.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.watchlist.WatchlistGroup;
import com.jijing.fund.domain.watchlist.WatchlistItem;
import com.jijing.fund.domain.watchlist.WatchlistRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 自选分组和条目。更新、删除都带版本条件，版本不符返回 false，不抛并发异常。
 * 同一分组重复加入同一基金时，数据库唯一约束被收成“已在自选中”。
 * 标签 JSON 写失败会抛出；读失败变成空列表。没有 HTTP 超时。
 */
public class JdbcWatchlistRepository implements WatchlistRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    /** JSON 用于标签列。 */
    public JdbcWatchlistRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 分组按排序和创建时间，每组再装上条目。 */
    @Override
    public List<WatchlistGroup> findByOwner(UserId owner) {
        return jdbc.query(
                "SELECT * FROM watchlist_group WHERE owner_user_id=? ORDER BY sort_order,created_at",
                this::group, owner.value()).stream().map(item -> withItems(owner, item)).toList();
    }

    /** 编号存在但不属于该用户时为空。 */
    @Override
    public Optional<WatchlistGroup> findByIdAndOwner(String id, UserId owner) {
        List<WatchlistGroup> rows = jdbc.query(
                "SELECT * FROM watchlist_group WHERE group_id=? AND owner_user_id=?",
                this::group, id, owner.value());
        return rows.stream().findFirst().map(item -> withItems(owner, item));
    }

    /** 插入分组。规范化名称用于后续查重，展示名保持原样。 */
    @Override
    public void saveGroup(WatchlistGroup group) {
        jdbc.update("""
                INSERT INTO watchlist_group(group_id,owner_user_id,normalized_name,display_name,sort_order,version,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?)
                """, group.groupId(), group.ownerUserId().value(), normalize(group.displayName()),
                group.displayName(), group.sortOrder(), group.version(), ts(group.createdAt()),
                ts(group.updatedAt()));
    }

    /** 版本不匹配时更新 0 行并返回 false。 */
    @Override
    public boolean updateGroup(WatchlistGroup group, long expected) {
        return jdbc.update("""
                UPDATE watchlist_group
                SET display_name=?,normalized_name=?,sort_order=?,version=?,updated_at=?
                WHERE group_id=? AND owner_user_id=? AND version=?
                """, group.displayName(), normalize(group.displayName()), group.sortOrder(), group.version(),
                ts(group.updatedAt()), group.groupId(), group.ownerUserId().value(), expected) > 0;
    }

    /** 版本不符时不删条目。版本相符时先删条目再删分组。 */
    @Override
    public boolean deleteGroup(UserId owner, String groupId, long expected) {
        Integer matched = jdbc.queryForObject(
                "SELECT COUNT(*) FROM watchlist_group WHERE group_id=? AND owner_user_id=? AND version=?",
                Integer.class, groupId, owner.value(), expected);
        if (matched == null || matched == 0) {
            return false;
        }
        jdbc.update("DELETE FROM watchlist_item WHERE group_id=?", groupId);
        return jdbc.update(
                "DELETE FROM watchlist_group WHERE group_id=? AND owner_user_id=? AND version=?",
                groupId, owner.value(), expected) > 0;
    }

    /**
     * 通过所属分组的所有者约束插入。唯一键冲突说明这只基金已在该分组。
     */
    @Override
    public void saveItem(UserId owner, String groupId, WatchlistItem item) {
        try {
            jdbc.update("""
                    INSERT INTO watchlist_item(item_id,group_id,fund_code,note,tags_json,sort_order,version,created_at,updated_at)
                    SELECT ?,group_id,?,?,?,?,?,?,? FROM watchlist_group WHERE group_id=? AND owner_user_id=?
                    """, item.itemId(), item.fundCode().value(), item.note(), json(item.tags()), item.sortOrder(),
                    item.version(), ts(item.createdAt()), ts(item.updatedAt()), groupId, owner.value());
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("fund already exists in watchlist");
        }
    }

    /** 条目更新必须同时匹配分组所有者和版本。 */
    @Override
    public boolean updateItem(UserId owner, String groupId, WatchlistItem item, long expected) {
        return jdbc.update("""
                UPDATE watchlist_item wi
                JOIN watchlist_group wg ON wi.group_id=wg.group_id
                SET wi.note=?,wi.tags_json=?,wi.sort_order=?,wi.version=?,wi.updated_at=?
                WHERE wi.item_id=? AND wi.group_id=? AND wg.owner_user_id=? AND wi.version=?
                """, item.note(), json(item.tags()), item.sortOrder(), item.version(), ts(item.updatedAt()),
                item.itemId(), groupId, owner.value(), expected) > 0;
    }

    /** 版本或所有者不符时删除 0 行。 */
    @Override
    public boolean deleteItem(UserId owner, String group, String item, long version) {
        return jdbc.update("""
                DELETE wi FROM watchlist_item wi
                JOIN watchlist_group wg ON wi.group_id=wg.group_id
                WHERE wi.item_id=? AND wi.group_id=? AND wg.owner_user_id=? AND wi.version=?
                """, item, group, owner.value(), version) > 0;
    }

    /** 查询该组条目并组装完整分组。 */
    private WatchlistGroup withItems(UserId owner, WatchlistGroup group) {
        List<WatchlistItem> items = jdbc.query(
                "SELECT * FROM watchlist_item WHERE group_id=? ORDER BY sort_order,created_at",
                this::item, group.groupId());
        return new WatchlistGroup(group.groupId(), owner, group.displayName(), group.sortOrder(), group.version(),
                items, group.createdAt(), group.updatedAt());
    }

    /** 分组行先不带条目，避免在映射器里再查库。 */
    private WatchlistGroup group(ResultSet row, int ignored) throws SQLException {
        return new WatchlistGroup(row.getString("group_id"), new UserId(row.getString("owner_user_id")),
                row.getString("display_name"), row.getInt("sort_order"), row.getLong("version"), List.of(),
                instant(row.getTimestamp("created_at")), instant(row.getTimestamp("updated_at")));
    }

    /** 条目行。标签坏 JSON 变成空列表。 */
    private WatchlistItem item(ResultSet row, int ignored) throws SQLException {
        return new WatchlistItem(row.getString("item_id"), new FundCode(row.getString("fund_code")),
                row.getString("note"), tags(row.getString("tags_json")), row.getInt("sort_order"),
                row.getLong("version"), instant(row.getTimestamp("created_at")),
                instant(row.getTimestamp("updated_at")));
    }

    /** 标签必须能序列化，否则条目不能落库。 */
    private String json(List<String> tags) {
        try {
            return json.writeValueAsString(tags);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 读失败不当成查询失败，调用方看到没有标签。 */
    private List<String> tags(String raw) {
        try {
            return raw == null ? List.of() : json.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 分组查重用的名称，去掉首尾空白并转小写。 */
    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /** JDBC 时间戳。 */
    private static Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }

    /** 时间列。 */
    private static Instant instant(Timestamp value) {
        return value.toInstant();
    }
}
