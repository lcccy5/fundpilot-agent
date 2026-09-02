package com.jijing.fund.infrastructure.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.domain.identity.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Internal HS256 codec. The signing key is never persisted or logged. */
public final class HmacJwtTokenCodec implements AccessTokenIssuer {
    private final byte[] key;private final String issuer,audience,keyId;private final ObjectMapper mapper;
    public HmacJwtTokenCodec(String secret,String issuer,String audience,String keyId,ObjectMapper mapper){if(secret==null||secret.length()<32)throw new IllegalArgumentException("JWT signing key must be at least 32 characters");this.key=secret.getBytes(StandardCharsets.UTF_8);this.issuer=issuer;this.audience=audience;this.keyId=keyId;this.mapper=mapper;}
    @Override public String issue(UserAccount account,String sessionId,Instant expiresAt){try{
        String header=b64(mapper.writeValueAsBytes(Map.of("alg","HS256","typ","JWT","kid",keyId)));
        Map<String,Object> claims=new LinkedHashMap<>();claims.put("sub",account.userId().value());claims.put("iss",issuer);claims.put("aud",audience);claims.put("sid",sessionId);claims.put("tv",account.tokenVersion());claims.put("roles",account.roles().stream().map(Enum::name).sorted().toList());claims.put("iat",Instant.now().getEpochSecond());claims.put("exp",expiresAt.getEpochSecond());
        String body=b64(mapper.writeValueAsBytes(claims));String signed=header+"."+body;return signed+"."+b64(hmac(signed));
    }catch(Exception e){throw new IllegalStateException("cannot issue access token",e);}}
    public AuthenticatedUser verify(String token,Instant now){return verifyDetails(token,now).user();}
    public VerifiedAccessToken verifyDetails(String token,Instant now){try{
        String[] parts=token.split("\\.");if(parts.length!=3||!MessageDigest.isEqual(hmac(parts[0]+"."+parts[1]),b64d(parts[2])))throw new IllegalArgumentException("invalid access token");
        Map<String,Object> c=mapper.readValue(b64d(parts[1]),new TypeReference<>(){});if(!issuer.equals(c.get("iss"))||!audience.equals(c.get("aud"))||((Number)c.get("exp")).longValue()<=now.getEpochSecond())throw new IllegalArgumentException("access token expired");
        Set<UserRole> roles=new HashSet<>();Object raw=c.get("roles");if(raw instanceof Collection<?> values)for(Object value:values)roles.add(UserRole.valueOf(String.valueOf(value)));
        Object tokenVersion=c.get("tv");if(!(tokenVersion instanceof Number)||c.get("sub")==null||c.get("sid")==null)throw new IllegalArgumentException("invalid access token claims");
        return new VerifiedAccessToken(new AuthenticatedUser(new UserId(String.valueOf(c.get("sub"))),roles,String.valueOf(c.get("sid"))),((Number)tokenVersion).intValue());
    }catch(Exception e){throw new IllegalArgumentException("invalid access token",e);}}
    private byte[] hmac(String text)throws Exception{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));}
    private static String b64(byte[] value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
    private static byte[] b64d(String value){return Base64.getUrlDecoder().decode(value);}
    public record VerifiedAccessToken(AuthenticatedUser user,int tokenVersion){}
}
