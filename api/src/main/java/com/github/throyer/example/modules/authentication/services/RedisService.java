package com.github.throyer.example.modules.authentication.services;

import static com.github.throyer.example.modules.infra.environments.SecurityEnvironments.REFRESH_TOKEN_EXPIRATION_IN_DAYS;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RedisService {

    private static final String REFRESH_TOKEN_PREFIX = "refresh_token:";
    private static final String USER_REFRESH_TOKENS_PREFIX = "user_refresh_tokens:";
    private static final String BLACKLIST_PREFIX = "blacklist:";
    private static final String REFRESH_TOKEN_BLACKLIST_PREFIX = "refresh_blacklist:";
    private static final String RATE_LIMIT_PREFIX = "rate_limit:";
    
    private static final String FIELD_USER_ID = "user_id";
    private static final String FIELD_EXPIRES_AT = "expires_at";
    
    private static final long RATE_LIMIT_WINDOW_SECONDS = 60;
    private static final int MAX_REQUESTS_PER_MINUTE = 60;

    private final StringRedisTemplate stringRedisTemplate;
    private final HashOperations<String, String, String> hashOperations;

    public RedisService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.hashOperations = stringRedisTemplate.opsForHash();
    }

    public void saveRefreshToken(Long userId, String tokenCode, LocalDateTime expiresAt) {
        String tokenKey = REFRESH_TOKEN_PREFIX + tokenCode;
        String userTokensKey = USER_REFRESH_TOKENS_PREFIX + userId;
        
        String expiresAtStr = expiresAt.atZone(ZoneId.systemDefault()).toInstant().toString();
        
        hashOperations.put(tokenKey, FIELD_USER_ID, userId.toString());
        hashOperations.put(tokenKey, FIELD_EXPIRES_AT, expiresAtStr);
        
        long ttlDays = REFRESH_TOKEN_EXPIRATION_IN_DAYS;
        stringRedisTemplate.expire(tokenKey, ttlDays, TimeUnit.DAYS);
        
        stringRedisTemplate.opsForSet().add(userTokensKey, tokenCode);
        stringRedisTemplate.expire(userTokensKey, ttlDays, TimeUnit.DAYS);
        
        log.debug("Saved refresh token for user: {}, code: {}", userId, tokenCode);
    }

    public Optional<Long> getUserIdByRefreshToken(String tokenCode) {
        String key = REFRESH_TOKEN_PREFIX + tokenCode;
        String userIdStr = hashOperations.get(key, FIELD_USER_ID);
        
        if (userIdStr == null) {
            return Optional.empty();
        }
        
        try {
            return Optional.of(Long.parseLong(userIdStr));
        } catch (NumberFormatException e) {
            log.error("Invalid user_id in refresh token: {}", userIdStr, e);
            return Optional.empty();
        }
    }

    public Optional<LocalDateTime> getRefreshTokenExpiration(String tokenCode) {
        String key = REFRESH_TOKEN_PREFIX + tokenCode;
        String expiresAtStr = hashOperations.get(key, FIELD_EXPIRES_AT);
        
        if (expiresAtStr == null) {
            return Optional.empty();
        }
        
        try {
            LocalDateTime expiresAt = LocalDateTime.parse(expiresAtStr.replace("Z", ""))
                .atZone(java.time.ZoneOffset.UTC)
                .withZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime();
            return Optional.of(expiresAt);
        } catch (Exception e) {
            log.error("Error parsing expiration date: {}", expiresAtStr, e);
            return Optional.empty();
        }
    }

    public boolean refreshTokenExists(String tokenCode) {
        String key = REFRESH_TOKEN_PREFIX + tokenCode;
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    }

    public void deleteRefreshToken(String tokenCode) {
        Optional<Long> userIdOpt = getUserIdByRefreshToken(tokenCode);
        
        String tokenKey = REFRESH_TOKEN_PREFIX + tokenCode;
        stringRedisTemplate.delete(tokenKey);
        
        userIdOpt.ifPresent(userId -> {
            String userTokensKey = USER_REFRESH_TOKENS_PREFIX + userId;
            stringRedisTemplate.opsForSet().remove(userTokensKey, tokenCode);
        });
        
        log.debug("Deleted refresh token: {}", tokenCode);
    }

    public void deleteAllRefreshTokens(Long userId) {
        String userTokensKey = USER_REFRESH_TOKENS_PREFIX + userId;
        Set<String> tokenCodes = stringRedisTemplate.opsForSet().members(userTokensKey);
        
        if (tokenCodes != null && !tokenCodes.isEmpty()) {
            for (String tokenCode : tokenCodes) {
                String tokenKey = REFRESH_TOKEN_PREFIX + tokenCode;
                stringRedisTemplate.delete(tokenKey);
            }
        }
        
        stringRedisTemplate.delete(userTokensKey);
        log.debug("Deleted all refresh tokens for user: {}", userId);
    }

    public void addToBlacklist(String jti, long ttlSeconds) {
        String key = BLACKLIST_PREFIX + jti;
        stringRedisTemplate.opsForValue().set(key, "true", ttlSeconds, TimeUnit.SECONDS);
        log.debug("Added token to blacklist: {}, TTL: {}s", jti, ttlSeconds);
    }

    public boolean isBlacklisted(String jti) {
        String key = BLACKLIST_PREFIX + jti;
        Boolean exists = stringRedisTemplate.hasKey(key);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<Long> getRemainingTtl(String jti) {
        String key = BLACKLIST_PREFIX + jti;
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttl != null && ttl > 0) {
            return Optional.of(ttl);
        }
        return Optional.empty();
    }

    public boolean tryAcquireRateLimit(Long userId) {
        return tryAcquireRateLimit("user_" + userId);
    }

    public boolean tryAcquireRateLimit(String ip) {
        String key = RATE_LIMIT_PREFIX + ip;
        
        Long current = stringRedisTemplate.opsForValue().increment(key);
        
        if (current == null || current == 1) {
            stringRedisTemplate.expire(key, RATE_LIMIT_WINDOW_SECONDS, TimeUnit.SECONDS);
        }
        
        boolean allowed = current != null && current <= MAX_REQUESTS_PER_MINUTE;
        
        if (!allowed) {
            log.debug("Rate limit exceeded for: {}", ip);
        }
        
        return allowed;
    }

    public long getCurrentRateLimitCount(Long userId) {
        return getCurrentRateLimitCount("user_" + userId);
    }

    public long getCurrentRateLimitCount(String ip) {
        String key = RATE_LIMIT_PREFIX + ip;
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public Optional<Long> getRateLimitRemainingTime(Long userId) {
        return getRateLimitRemainingTime("user_" + userId);
    }

    public Optional<Long> getRateLimitRemainingTime(String ip) {
        String key = RATE_LIMIT_PREFIX + ip;
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttl != null && ttl > 0) {
            return Optional.of(ttl);
        }
        return Optional.empty();
    }

    public Set<String> getAllRefreshTokenCodes(Long userId) {
        String key = USER_REFRESH_TOKENS_PREFIX + userId;
        return stringRedisTemplate.opsForSet().members(key);
    }

    public void addRefreshTokenToBlacklist(String tokenCode, long ttlSeconds) {
        String key = REFRESH_TOKEN_BLACKLIST_PREFIX + tokenCode;
        stringRedisTemplate.opsForValue().set(key, "true", ttlSeconds, TimeUnit.SECONDS);
        log.info("Added refresh token to blacklist: {}, TTL: {}s", tokenCode, ttlSeconds);
    }

    public boolean isRefreshTokenBlacklisted(String tokenCode) {
        String key = REFRESH_TOKEN_BLACKLIST_PREFIX + tokenCode;
        Boolean exists = stringRedisTemplate.hasKey(key);
        return Boolean.TRUE.equals(exists);
    }

    public void addAllUserRefreshTokensToBlacklist(Long userId) {
        Set<String> tokenCodes = getAllRefreshTokenCodes(userId);
        if (tokenCodes != null && !tokenCodes.isEmpty()) {
            long ttlSeconds = REFRESH_TOKEN_EXPIRATION_IN_DAYS * 24 * 60 * 60;
            for (String tokenCode : tokenCodes) {
                addRefreshTokenToBlacklist(tokenCode, ttlSeconds);
            }
        }
    }
}
