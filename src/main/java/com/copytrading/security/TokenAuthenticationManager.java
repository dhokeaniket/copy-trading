package com.copytrading.security;

import com.copytrading.auth.JwtService;
import io.jsonwebtoken.Claims;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
            String userId = claims.getSubject(); // UUID string
            String role = claims.get("role", String.class);
            var authority = new SimpleGrantedAuthority("ROLE_" + role);
            // Principal = userId (UUID string), credentials = token
            return Mono.just(new UsernamePasswordAuthenticationToken(userId, token, List.of(authority)));
        } catch (Exception e) {
            return Mono.error(new BadCredentialsException("Invalid or expired token", e));
        }
    }
}
