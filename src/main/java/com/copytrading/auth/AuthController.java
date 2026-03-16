package com.copytrading.auth;

import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final UserAccountRepository users;
  private final JwtService jwtService;
  private final PasswordEncoder encoder;

  public AuthController(UserAccountRepository users, JwtService jwtService, PasswordEncoder encoder) {
    this.users = users;
    this.jwtService = jwtService;
    this.encoder = encoder;
  }

  @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<Map<String, String>> login(@RequestBody Map<String, String> body) {
    String username = body.get("username");
    String password = body.get("password");
    return users.findByUsername(username)
        .filter(UserAccount::isActive)
        .filter(u -> encoder.matches(password, u.getPasswordHash()))
        .map(u -> Map.of("token", jwtService.generateToken(u.getUsername(), u.getRole(), Map.of())))
        .switchIfEmpty(Mono.error(new RuntimeException("invalid_credentials")));
  }
}
