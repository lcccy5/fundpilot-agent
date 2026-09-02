package com.jijing.fund.application.watchlist;
import com.jijing.fund.domain.identity.*;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.watchlist.*;
import java.time.*;import java.util.*;
import org.springframework.transaction.annotation.Transactional;

public class WatchlistApplicationService implements WatchlistUseCase {
    private final WatchlistRepository repository;private final Clock clock;
    public WatchlistApplicationService(WatchlistRepository repository,Clock clock){this.repository=repository;this.clock=clock;}
    @Override public List<WatchlistGroup> list(AuthenticatedUser actor){return repository.findByOwner(actor.userId());}
    @Override @Transactional public WatchlistGroup create(AuthenticatedUser actor,String name){Instant now=clock.instant();String display=required(name,80);var g=new WatchlistGroup(UUID.randomUUID().toString(),actor.userId(),display,repository.findByOwner(actor.userId()).size(),0,List.of(),now,now);repository.saveGroup(g);return g;}
    @Override @Transactional public WatchlistGroup rename(AuthenticatedUser actor,String groupId,String name,long version){
        var group=owned(actor,groupId);Instant now=clock.instant();var next=new WatchlistGroup(group.groupId(),group.ownerUserId(),required(name,80),group.sortOrder(),version+1,group.items(),group.createdAt(),now);
        if(!repository.updateGroup(next,version))throw new WatchlistConflictException("watchlist was updated concurrently");return owned(actor,groupId);
    }
    @Override @Transactional public void delete(AuthenticatedUser actor,String groupId,long version){if(!repository.deleteGroup(actor.userId(),groupId,version))throw new WatchlistConflictException("watchlist was updated concurrently");}
    @Override @Transactional public WatchlistGroup add(AuthenticatedUser actor,String groupId,String fundCode,String note,List<String> tags){
        var group=owned(actor,groupId);Instant now=clock.instant();var item=new WatchlistItem(UUID.randomUUID().toString(),new FundCode(fundCode),optional(note,500),normalizeTags(tags),group.items().size(),0,now,now);
        try{repository.saveItem(actor.userId(),groupId,item);}catch(IllegalArgumentException e){throw new WatchlistConflictException(e.getMessage());}
        return owned(actor,groupId);
    }
    @Override @Transactional public WatchlistGroup updateItem(AuthenticatedUser actor,String groupId,String itemId,String note,List<String> tags,long version){
        var group=owned(actor,groupId);var item=group.items().stream().filter(i->i.itemId().equals(itemId)).findFirst().orElseThrow(()->new WatchlistNotFoundException("watchlist item not found"));
        Instant now=clock.instant();var next=new WatchlistItem(item.itemId(),item.fundCode(),optional(note,500),normalizeTags(tags),item.sortOrder(),version+1,item.createdAt(),now);
        if(!repository.updateItem(actor.userId(),groupId,next,version))throw new WatchlistConflictException("watchlist item was updated concurrently");return owned(actor,groupId);
    }
    @Override @Transactional public WatchlistGroup removeItem(AuthenticatedUser actor,String groupId,String itemId,long version){
        owned(actor,groupId);if(!repository.deleteItem(actor.userId(),groupId,itemId,version))throw new WatchlistConflictException("watchlist item was updated concurrently");return owned(actor,groupId);
    }
    @Override @Transactional public MergeLocalResult mergeLocal(AuthenticatedUser actor,List<String> fundCodes){
        var groups=repository.findByOwner(actor.userId());WatchlistGroup target=groups.isEmpty()?create(actor,"默认分组"):groups.getFirst();
        int added=0,existing=0,rejected=0;var rejectedCodes=new ArrayList<String>();
        for(String code:fundCodes==null?List.<String>of():fundCodes){
            if(code==null||!code.matches("\\d{6}")){rejected++;rejectedCodes.add(code==null?"":code);continue;}
            boolean present=owned(actor,target.groupId()).items().stream().anyMatch(i->i.fundCode().value().equals(code));
            if(present){existing++;continue;}
            try{add(actor,target.groupId(),code,null,List.of());added++;}catch(RuntimeException e){rejected++;rejectedCodes.add(code);}
        }
        return new MergeLocalResult(target.groupId(),added,existing,rejected,List.copyOf(rejectedCodes));
    }
    private WatchlistGroup owned(AuthenticatedUser actor,String groupId){return repository.findByIdAndOwner(groupId,actor.userId()).orElseThrow(()->new WatchlistNotFoundException("watchlist not found"));}
    private static String required(String value,int limit){String out=optional(value,limit);if(out==null||out.isBlank())throw new WatchlistException("name is required");return out;}
    private static String optional(String value,int limit){if(value==null)return null;String out=value.trim();if(out.length()>limit)throw new WatchlistException("value is too long");return out;}
    private static List<String> normalizeTags(List<String> tags){if(tags==null)return List.of();return tags.stream().filter(Objects::nonNull).map(String::trim).filter(s->!s.isBlank()).distinct().limit(10).toList();}
}
