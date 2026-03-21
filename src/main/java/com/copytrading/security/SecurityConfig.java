package com.copytrading.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;

@Configuration
public class SecurityConfig {
  @Bean
  public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                       TokenAuthenticationManager authManager,
                                                       BearerTokenServerAuthenticationConverter converter) {
    AuthenticationWebFilter filter = new AuthenticationWebFilter(authManager);
    filter.setServerAuthenticationConverter(converter);
    return http
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .authorizeExchange(ex -> ex
            .pathMatchers("/", "/health").permitAll()
            .pathMatchers("/api/auth/**").permitAll()
            .pathMatchers("/actuator/**").hasRole("ADMIN")
            .anyExchange().authenticated()
        )
        .addFilterAt(filter, SecurityWebFiltersOrder.AUTHENTICATION)
        .build();
  }
}
