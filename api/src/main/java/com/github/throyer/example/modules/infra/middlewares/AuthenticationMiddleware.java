package com.github.throyer.example.modules.infra.middlewares;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.github.throyer.example.modules.authentication.services.RequestAuthorizer;

@Order(1)
@Component
public class AuthenticationMiddleware extends OncePerRequestFilter {

  private final RequestAuthorizer requestAuthorizer;

  public AuthenticationMiddleware(RequestAuthorizer requestAuthorizer) {
    this.requestAuthorizer = requestAuthorizer;
  }

  @Override
  protected void doFilterInternal(
    HttpServletRequest request,
    HttpServletResponse response,
    FilterChain filter
  ) throws ServletException, IOException {
    requestAuthorizer.tryAuthorizeRequest(request, response);
    filter.doFilter(request, response);
  }
}
