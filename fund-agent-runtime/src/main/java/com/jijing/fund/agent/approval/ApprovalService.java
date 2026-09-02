package com.jijing.fund.agent.approval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** 实现 ApprovalService 所代表的 Agent 运行时职责。 */
public final class ApprovalService {
    
    /** 判断 isValid 对应的条件是否成立。 */
    public boolean isValid(String storedParameterHash,String currentParameters,Instant expiresAt,Instant now,Instant usedAt){
        if(usedAt!=null)return false;
        if(expiresAt==null||now==null||now.isAfter(expiresAt))return false;
        return Objects.equals(storedParameterHash,hash(currentParameters));
    }
    
    /** 判断 hash 对应的条件是否成立。 */
    public String hash(String parameters){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((parameters==null?"":parameters).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
}
