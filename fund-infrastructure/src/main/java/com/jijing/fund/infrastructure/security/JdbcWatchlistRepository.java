package com.jijing.fund.infrastructure.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.watchlist.*;
import java.sql.Timestamp;import java.time.Instant;import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcWatchlistRepository implements WatchlistRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public JdbcWatchlistRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    @Override public List<WatchlistGroup> findByOwner(UserId owner){return jdbc.query("SELECT * FROM watchlist_group WHERE owner_user_id=? ORDER BY sort_order,created_at",this::group,owner.value()).stream().map(g->withItems(owner,g)).toList();}
    @Override public Optional<WatchlistGroup> findByIdAndOwner(String id,UserId owner){var rows=jdbc.query("SELECT * FROM watchlist_group WHERE group_id=? AND owner_user_id=?",this::group,id,owner.value());return rows.stream().findFirst().map(g->withItems(owner,g));}
    @Override public void saveGroup(WatchlistGroup g){jdbc.update("INSERT INTO watchlist_group(group_id,owner_user_id,normalized_name,display_name,sort_order,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",g.groupId(),g.ownerUserId().value(),normalize(g.displayName()),g.displayName(),g.sortOrder(),g.version(),ts(g.createdAt()),ts(g.updatedAt()));}
    @Override public boolean updateGroup(WatchlistGroup g,long expected){return jdbc.update("UPDATE watchlist_group SET display_name=?,normalized_name=?,sort_order=?,version=?,updated_at=? WHERE group_id=? AND owner_user_id=? AND version=?",g.displayName(),normalize(g.displayName()),g.sortOrder(),g.version(),ts(g.updatedAt()),g.groupId(),g.ownerUserId().value(),expected)>0;}
    @Override public boolean deleteGroup(UserId owner,String groupId,long expected){Integer ok=jdbc.queryForObject("SELECT COUNT(*) FROM watchlist_group WHERE group_id=? AND owner_user_id=? AND version=?",Integer.class,groupId,owner.value(),expected);if(ok==null||ok==0)return false;jdbc.update("DELETE FROM watchlist_item WHERE group_id=?",groupId);return jdbc.update("DELETE FROM watchlist_group WHERE group_id=? AND owner_user_id=? AND version=?",groupId,owner.value(),expected)>0;}
    @Override public void saveItem(UserId owner,String groupId,WatchlistItem i){try{jdbc.update("INSERT INTO watchlist_item(item_id,group_id,fund_code,note,tags_json,sort_order,version,created_at,updated_at) SELECT ?,group_id,?,?,?,?,?,?,? FROM watchlist_group WHERE group_id=? AND owner_user_id=?",i.itemId(),i.fundCode().value(),i.note(),json(i.tags()),i.sortOrder(),i.version(),ts(i.createdAt()),ts(i.updatedAt()),groupId,owner.value());}catch(org.springframework.dao.DuplicateKeyException e){throw new IllegalArgumentException("fund already exists in watchlist");}}
    @Override public boolean updateItem(UserId owner,String groupId,WatchlistItem i,long expected){return jdbc.update("UPDATE watchlist_item wi JOIN watchlist_group wg ON wi.group_id=wg.group_id SET wi.note=?,wi.tags_json=?,wi.sort_order=?,wi.version=?,wi.updated_at=? WHERE wi.item_id=? AND wi.group_id=? AND wg.owner_user_id=? AND wi.version=?",i.note(),json(i.tags()),i.sortOrder(),i.version(),ts(i.updatedAt()),i.itemId(),groupId,owner.value(),expected)>0;}
    @Override public boolean deleteItem(UserId owner,String group,String item,long version){return jdbc.update("DELETE wi FROM watchlist_item wi JOIN watchlist_group wg ON wi.group_id=wg.group_id WHERE wi.item_id=? AND wi.group_id=? AND wg.owner_user_id=? AND wi.version=?",item,group,owner.value(),version)>0;}
    private WatchlistGroup withItems(UserId owner,WatchlistGroup g){var items=jdbc.query("SELECT * FROM watchlist_item WHERE group_id=? ORDER BY sort_order,created_at",this::item,g.groupId());return new WatchlistGroup(g.groupId(),owner,g.displayName(),g.sortOrder(),g.version(),items,g.createdAt(),g.updatedAt());}
    private WatchlistGroup group(java.sql.ResultSet r,int n)throws java.sql.SQLException{return new WatchlistGroup(r.getString("group_id"),new UserId(r.getString("owner_user_id")),r.getString("display_name"),r.getInt("sort_order"),r.getLong("version"),List.of(),instant(r.getTimestamp("created_at")),instant(r.getTimestamp("updated_at")));}
    private WatchlistItem item(java.sql.ResultSet r,int n)throws java.sql.SQLException{return new WatchlistItem(r.getString("item_id"),new FundCode(r.getString("fund_code")),r.getString("note"),tags(r.getString("tags_json")),r.getInt("sort_order"),r.getLong("version"),instant(r.getTimestamp("created_at")),instant(r.getTimestamp("updated_at")));}
    private String json(List<String> tags){try{return json.writeValueAsString(tags);}catch(Exception e){throw new IllegalStateException(e);}}
    private List<String> tags(String raw){try{return raw==null?List.of():json.readValue(raw,new TypeReference<>(){});}catch(Exception e){return List.of();}}
    private static String normalize(String value){return value.trim().toLowerCase(Locale.ROOT);}
    private static Timestamp ts(Instant v){return Timestamp.from(v);}private static Instant instant(Timestamp v){return v.toInstant();}
}
