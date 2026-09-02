package com.jijing.fund.knowledge.service;

import java.security.MessageDigest;
import java.util.HexFormat;

public final class KnowledgeHash {
    private KnowledgeHash(){}
    public static String sha256(byte[] value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
    public static String sha256(String value){return sha256(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}
