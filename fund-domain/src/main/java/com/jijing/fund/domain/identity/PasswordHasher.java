package com.jijing.fund.domain.identity;

public interface PasswordHasher { String hash(String rawPassword); boolean matches(String rawPassword,String passwordHash); }
