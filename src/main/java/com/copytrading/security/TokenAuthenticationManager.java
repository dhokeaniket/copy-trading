package com.copytrading.security;


import com.copytrading.auth.JwtService;
import com.copytrading.auth.Role;
import io.jsonwebtoken.Claims;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class TokenAuthenticationManager implements ReactiveAuthenticationManager {
  private final JwtService jwtService;

  public TokenAuthenticationManager(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  public Mono<Authentication> authenticate(Authentication authentication) {
    String token = (String) authentication.getCredentials();
    try {
      Claims claims = jwtService.parse(token);
      String subject = claims.getSubject();
      String roleStr = claims.get("role", String.class);
      Role role = Role.valueOf(roleStr);
      GrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + role.name());
      return Mono.just(new UsernamePasswordAuthenticationToken(subject, token, List.of(authority)));
    } catch (Exception e) {
      return Mono.error(new BadCredentialsException("invalid_token", e));
    }
  }
}
