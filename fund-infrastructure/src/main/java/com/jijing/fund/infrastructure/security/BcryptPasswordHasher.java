package com.jijing.fund.infrastructure.security;

import com.jijing.fund.domain.identity.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class BcryptPasswordHasher implements PasswordHasher {
    private final BCryptPasswordEncoder encoder;
    public BcryptPasswordHasher(int strength){encoder=new BCryptPasswordEncoder(strength);}
    @Override public String hash(String rawPassword){return encoder.encode(rawPassword);}
    @Override public boolean matches(String rawPassword,String passwordHash){return rawPassword!=null&&passwordHash!=null&&encoder.matches(rawPassword,passwordHash);}
}
