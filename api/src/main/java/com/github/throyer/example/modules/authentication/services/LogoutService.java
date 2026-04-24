package com.github.throyer.example.modules.authentication.services;

import static com.github.throyer.example.modules.infra.environments.SecurityEnvironments.JWT;
import static com.github.throyer.example.modules.infra.environments.SecurityEnvironments.TOKEN_SECRET;
import static java.util.Objects.nonNull;

import org.springframework.stereotype.Service;

import com.github.throyer.example.modules.authentication.models.Authorized;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class LogoutService {

    private final RedisService redisService;

    public LogoutService(RedisService redisService) {
        this.redisService = redisService;
    }

    public void logout(String token, Authorized authorized) {
        if (nonNull(token)) {
            String cleanToken = extractToken(token);
            if (nonNull(cleanToken)) {
                JWT.getJti(cleanToken, TOKEN_SECRET).ifPresent(jti -> {
                    long remainingSeconds = JWT.getRemainingSeconds(cleanToken, TOKEN_SECRET);
                    if (remainingSeconds > 0) {
                        redisService.addToBlacklist(jti, remainingSeconds);
                        log.info("Access token added to blacklist: {}, remaining TTL: {}s", jti, remainingSeconds);
                    }
                });
            }
        }

        if (nonNull(authorized) && nonNull(authorized.getId())) {
            Long userId = authorized.getId();
            redisService.addAllUserRefreshTokensToBlacklist(userId);
            redisService.deleteAllRefreshTokens(userId);
            log.info("User {} logged out, all refresh tokens blacklisted and deleted", userId);
        }
    }

    private String extractToken(String header) {
        if (nonNull(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return header;
    }
}
