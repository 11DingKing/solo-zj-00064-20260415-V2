package com.github.throyer.example.modules.authentication.controllers;

import static com.github.throyer.example.modules.infra.http.Responses.ok;
import static com.github.throyer.example.modules.infra.environments.SecurityEnvironments.AUTHORIZATION_HEADER;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.throyer.example.modules.authentication.models.Authorized;
import com.github.throyer.example.modules.authentication.services.LogoutService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Auth")
@RequestMapping("/api/auth")
public class AuthController {
  private final LogoutService logoutService;

  @Autowired
  public AuthController(LogoutService logoutService) {
    this.logoutService = logoutService;
  }

  @PostMapping("/logout")
  @Operation(summary = "Logout and invalidate tokens")
  public ResponseEntity<Map<String, String>> logout(
    HttpServletRequest request
  ) {
    String token = request.getHeader(AUTHORIZATION_HEADER);
    Authorized current = Authorized.current().orElse(null);
    
    logoutService.logout(token, current);
    
    return ok(Map.of("message", "Logged out successfully"));
  }
}
