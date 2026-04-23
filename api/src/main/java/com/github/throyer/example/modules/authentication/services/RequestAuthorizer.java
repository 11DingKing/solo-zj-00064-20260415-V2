package com.github.throyer.example.modules.authentication.services;

import static com.github.throyer.example.modules.infra.constants.MessagesConstants.BLACKLISTED_TOKEN_MESSAGE;
import static com.github.throyer.example.modules.infra.constants.MessagesConstants.TOO_MANY_REQUESTS_MESSAGE;
import static com.github.throyer.example.modules.infra.environments.SecurityEnvironments.JWT;
import static com.github.throyer.example.modules.infra.environments.SecurityEnvironments.TOKEN_SECRET;
import static com.github.throyer.example.modules.infra.http.Responses.expired;
import static com.github.throyer.example.modules.infra.http.Responses.forbidden;
import static com.github.throyer.example.modules.infra.http.Responses.tooManyRequests;
import static com.github.throyer.example.modules.infra.http.context.HttpContext.publicRoutes;
import static com.github.throyer.example.modules.shared.utils.InternationalizationUtils.message;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.github.throyer.example.modules.authentication.models.Authorized;
import com.github.throyer.example.modules.authentication.utils.Authorization;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RequestAuthorizer {
  
  private final RedisService redisService;

  public RequestAuthorizer(RedisService redisService) {
    this.redisService = redisService;
  }

  public void tryAuthorizeRequest(
    HttpServletRequest request,
    HttpServletResponse response
  ) {
    if (publicRoutes().anyMatch(request)) {
      return;
    }

    var token = Authorization.extract(request);

    if (isNull(token)) {
      return;
    }

    Optional<String> jtiOpt = JWT.getJti(token, TOKEN_SECRET);
    if (jtiOpt.isPresent() && redisService.isBlacklisted(jtiOpt.get())) {
      log.warn("Blacklisted token attempt: {}", jtiOpt.get());
      forbidden(response, message(BLACKLISTED_TOKEN_MESSAGE));
      return;
    }

    try {
      var authorized = JWT.decode(token, TOKEN_SECRET);
      SecurityContextHolder
        .getContext()
          .setAuthentication(authorized.getAuthentication());
      
      if (!checkRateLimit(authorized, response)) {
        SecurityContextHolder.clearContext();
      }
    } catch (Exception exception) {
      expired(response);
    }
  }

  private boolean checkRateLimit(Authorized authorized, HttpServletResponse response) {
    if (nonNull(authorized) && nonNull(authorized.getId())) {
      Long userId = authorized.getId();
      
      if (!redisService.tryAcquireRateLimit(userId)) {
        log.warn("Rate limit exceeded for user: {}", userId);
        tooManyRequests(response, message(TOO_MANY_REQUESTS_MESSAGE));
        return false;
      }
    }
    return true;
  }
}
