package com.jijing.fund.domain.identity;

import java.util.UUID;

public record UserId(String value) {
    public UserId { if(value==null||value.isBlank()) throw new IllegalArgumentException("userId is required"); UUID.fromString(value); }
    public static UserId random(){ return new UserId(UUID.randomUUID().toString()); }
}
