package com.jijing.fund.knowledge.port;
import java.util.Map;
public interface IndexGovernanceGateway {
    PreparedIndex rebuild(String rebuildId);
    void activate(String readAlias,String writeAlias,String previousIndex,String targetIndex);
    void rollback(String readAlias,String writeAlias,String currentIndex,String previousIndex);
    record PreparedIndex(String readAlias,String writeAlias,String previousIndex,String targetIndex,long expectedCount,long indexedCount,Map<String,Object>validation){}
}
