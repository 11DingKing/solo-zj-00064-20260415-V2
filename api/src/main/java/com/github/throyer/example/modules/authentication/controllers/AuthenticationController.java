package com.github.throyer.example.modules.authentication.controllers;

import static com.github.throyer.example.modules.infra.http.Responses.ok;
import static com.github.throyer.example.modules.infra.environments.SecurityEnvironments.AUTHORIZATION_HEADER;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.throyer.example.modules.authentication.dtos.CreateAuthenticationWithEmailAndPassword;
import com.github.throyer.example.modules.authentication.dtos.CreateAuthenticationWithRefreshToken;
import com.github.throyer.example.modules.authentication.models.Authentication;
import com.github.throyer.example.modules.authentication.models.Authorized;
import com.github.throyer.example.modules.authentication.services.CreateAuthenticationService;
import com.github.throyer.example.modules.authentication.services.LogoutService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Authentication")
@RequestMapping("/api/authentication")
public class AuthenticationController {
  private final CreateAuthenticationService service;
  private final LogoutService logoutService;

  @Autowired
  public AuthenticationController(
    CreateAuthenticationService service,
    LogoutService logoutService
  ) {
    this.service = service;
    this.logoutService = logoutService;
  }

  @PostMapping
  @Operation(summary = "Create a jwt token")
  public ResponseEntity<Authentication> create(
    @RequestBody
    @Valid
    CreateAuthenticationWithEmailAndPassword body
  ) {
    return ok(service.create(body));
  }

  @PostMapping("/refresh")
  @Operation(summary = "Create a new jwt token from refresh code")
  public ResponseEntity<Authentication> refresh(
    @RequestBody
    @Valid
    CreateAuthenticationWithRefreshToken body
  ) {
    return ok(service.create(body));
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