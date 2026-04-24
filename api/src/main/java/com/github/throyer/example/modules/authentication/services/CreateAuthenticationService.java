package com.github.throyer.example.modules.authentication.services;

import static com.github.throyer.example.modules.infra.constants.MessagesConstants.CREATE_SESSION_ERROR_MESSAGE;
import static com.github.throyer.example.modules.infra.constants.MessagesConstants.REFRESH_SESSION_ERROR_MESSAGE;
import static com.github.throyer.example.modules.infra.environments.SecurityEnvironments.JWT;
import static com.github.throyer.example.modules.infra.environments.SecurityEnvironments.REFRESH_TOKEN_EXPIRATION_IN_DAYS;
import static com.github.throyer.example.modules.infra.environments.SecurityEnvironments.TOKEN_EXPIRATION_IN_HOURS;
import static com.github.throyer.example.modules.infra.environments.SecurityEnvironments.TOKEN_SECRET;
import static com.github.throyer.example.modules.infra.http.Responses.forbidden;
import static com.github.throyer.example.modules.shared.utils.HashIdsUtils.encode;
import static com.github.throyer.example.modules.shared.utils.InternationalizationUtils.message;
import static java.time.LocalDateTime.now;
import static java.util.UUID.randomUUID;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.github.throyer.example.modules.authentication.dtos.CreateAuthenticationWithEmailAndPassword;
import com.github.throyer.example.modules.authentication.dtos.CreateAuthenticationWithRefreshToken;
import com.github.throyer.example.modules.authentication.models.Authentication;
import com.github.throyer.example.modules.users.dtos.UserInformation;
import com.github.throyer.example.modules.users.repositories.UserRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CreateAuthenticationService {
  private final UserRepository userRepository;
  private final RedisService redisService;

  @Autowired
  public CreateAuthenticationService(
    UserRepository userRepository,
    RedisService redisService
  ) {
    this.userRepository = userRepository;
    this.redisService = redisService;
  }
  
  public Authentication create(CreateAuthenticationWithEmailAndPassword body) {
    var user = userRepository.findByEmail(body.getEmail())
      .filter(databaseUser -> databaseUser.validatePassword(body.getPassword()))
        .orElseThrow(() -> forbidden(message(CREATE_SESSION_ERROR_MESSAGE)));
    
    var now = now();
    var expiresAt = now.plusHours(TOKEN_EXPIRATION_IN_HOURS);

    var accessToken = JWT.encode(
      encode(user.getId()),
      user.getAuthorities(),
      expiresAt,
      TOKEN_SECRET
    );

    var refreshTokenCode = randomUUID().toString();
    var refreshTokenExpiresAt = now.plusDays(REFRESH_TOKEN_EXPIRATION_IN_DAYS);

    redisService.deleteAllRefreshTokens(user.getId());
    redisService.saveRefreshToken(user.getId(), refreshTokenCode, refreshTokenExpiresAt);

    log.info("User {} logged in, refresh token saved to Redis", user.getEmail());

    return new Authentication(
      new UserInformation(user),
      accessToken,
      refreshTokenCode,
      expiresAt
    );
  }

  public Authentication create(CreateAuthenticationWithRefreshToken body) {
    var refreshTokenCode = body.getRefreshToken();
    
    if (redisService.isRefreshTokenBlacklisted(refreshTokenCode)) {
      log.warn("Attempt to use blacklisted refresh token: {}", refreshTokenCode);
      throw forbidden(message(REFRESH_SESSION_ERROR_MESSAGE));
    }
    
    var userIdOpt = redisService.getUserIdByRefreshToken(refreshTokenCode);
    if (userIdOpt.isEmpty()) {
      throw forbidden(message(REFRESH_SESSION_ERROR_MESSAGE));
    }

    var expirationOpt = redisService.getRefreshTokenExpiration(refreshTokenCode);
    if (expirationOpt.isEmpty() || expirationOpt.get().isBefore(now())) {
      redisService.deleteRefreshToken(refreshTokenCode);
      throw forbidden(message(REFRESH_SESSION_ERROR_MESSAGE));
    }

    var userId = userIdOpt.get();
    var user = userRepository.findById(userId)
      .orElseThrow(() -> forbidden(message(REFRESH_SESSION_ERROR_MESSAGE)));

    var now = now();
    var expiresAt = now.plusHours(TOKEN_EXPIRATION_IN_HOURS);

    var accessToken = JWT.encode(
      encode(user.getId()),
      user.getAuthorities(),
      expiresAt,
      TOKEN_SECRET
    );

    var newRefreshTokenCode = randomUUID().toString();
    var newRefreshTokenExpiresAt = now.plusDays(REFRESH_TOKEN_EXPIRATION_IN_DAYS);

    long refreshTokenTtlSeconds = REFRESH_TOKEN_EXPIRATION_IN_DAYS * 24 * 60 * 60;
    redisService.addRefreshTokenToBlacklist(refreshTokenCode, refreshTokenTtlSeconds);
    redisService.deleteRefreshToken(refreshTokenCode);
    redisService.saveRefreshToken(user.getId(), newRefreshTokenCode, newRefreshTokenExpiresAt);

    log.info("User {} refreshed token, old token blacklisted and invalidated", user.getEmail());

    return new Authentication(
      new UserInformation(user),
      accessToken,
      newRefreshTokenCode,
      expiresAt
    );
  }
}
