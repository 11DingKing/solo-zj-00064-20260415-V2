package com.github.throyer.example.modules.authentication.services;

import static io.jsonwebtoken.SignatureAlgorithm.HS256;
import static java.time.ZoneId.systemDefault;
import static java.util.Arrays.stream;
import static java.util.Date.from;
import static java.util.Objects.nonNull;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.github.throyer.example.modules.authentication.models.Authorized;
import com.github.throyer.example.modules.infra.environments.SecurityEnvironments;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

public class JsonWebToken {
  
  public static final String JTI_KEY = "jti";

  public String encode(
      String id,
      List<String> roles,
      LocalDateTime expiration,
      String secret
    ) {
    String jti = UUID.randomUUID().toString();
    return Jwts.builder()
      .setId(jti)
      .setSubject(id)
      .claim(SecurityEnvironments.ROLES_KEY_ON_JWT, String.join(",", roles))
      .setExpiration(from(expiration.atZone(systemDefault()).toInstant()))
      .signWith(HS256, secret)
      .compact();
  }

  public Authorized decode(String token, String secret) {
    var decoded = Jwts.parser().setSigningKey(secret).parseClaimsJws(token);

    var id = decoded.getBody().getSubject();

    var joinedRolesString = decoded.getBody().get(SecurityEnvironments.ROLES_KEY_ON_JWT).toString();
    var roles = joinedRolesString.split(",");
    var authorities = stream(roles).map(SimpleGrantedAuthority::new).toList();

    return new Authorized(id, authorities);
  }

  public Optional<String> getJti(String token, String secret) {
    try {
      var decoded = Jwts.parser().setSigningKey(secret).parseClaimsJws(token);
      String jti = decoded.getBody().getId();
      return Optional.ofNullable(jti);
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  public Optional<LocalDateTime> getExpiration(String token, String secret) {
    try {
      var decoded = Jwts.parser().setSigningKey(secret).parseClaimsJws(token);
      Date expiration = decoded.getBody().getExpiration();
      if (nonNull(expiration)) {
        return Optional.of(LocalDateTime.ofInstant(expiration.toInstant(), ZoneId.systemDefault()));
      }
      return Optional.empty();
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  public long getRemainingSeconds(String token, String secret) {
    try {
      var decoded = Jwts.parser().setSigningKey(secret).parseClaimsJws(token);
      Date expiration = decoded.getBody().getExpiration();
      if (nonNull(expiration)) {
        long remaining = expiration.getTime() - System.currentTimeMillis();
        return Math.max(0, remaining / 1000);
      }
      return 0;
    } catch (Exception e) {
      return 0;
    }
  }
}
