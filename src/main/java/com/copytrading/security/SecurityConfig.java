package com.copytrading.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            TokenAuthenticationManager authManager,
            BearerTokenServerAuthenticationConverter converter,
            CorsConfigurationSource corsSource) {

        AuthenticationWebFilter filter = new AuthenticationWebFilter(authManager);
        filter.setServerAuthenticationConverter(converter);
        filter.setRequiresAuthenticationMatcher(new NegatedServerWebExchangeMatcher(
                new OrServerWebExchangeMatcher(
                        new PathPatternParserServerWebExchangeMatcher("/"),
                        new PathPatternParserServerWebExchangeMatcher("/health"),
                        new PathPatternParserServerWebExchangeMatcher("/swagger-ui.html"),
                        new PathPatternParserServerWebExchangeMatcher("/swagger-ui/**"),
                        new PathPatternParserServerWebExchangeMatcher("/v3/api-docs/**"),
                        new PathPatternParserServerWebExchangeMatcher("/webjars/**"),
                        new PathPatternParserServerWebExchangeMatcher("/api/v1/auth/register"),
                        new PathPatternParserServerWebExchangeMatcher("/api/v1/auth/login"),
                        new PathPatternParserServerWebExchangeMatcher("/api/v1/auth/refresh-token"),
                        new PathPatternParserServerWebExchangeMatcher("/api/v1/auth/forgot-password"),
                        new PathPatternParserServerWebExchangeMatcher("/api/v1/auth/reset-password"),
                        new PathPatternParserServerWebExchangeMatcher("/api/v1/auth/send-otp"),
                        new PathPatternParserServerWebExchangeMatcher("/api/v1/auth/verify-otp"),
                        new PathPatternParserServerWebExchangeMatcher("/api/v1/brokers/callback"),
                        new PathPatternParserServerWebExchangeMatcher("/api/v1/brokers/postback/**"),
                        new PathPatternParserServerWebExchangeMatcher("/api/auth/**")
                )
        ));

        return http
                .cors(cors -> cors.configurationSource(corsSource))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((exchange, denied) -> {
                            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            return Mono.empty();
                        })
                )
                .authorizeExchange(ex -> ex
                        .pathMatchers("/", "/health").permitAll()
                        .pathMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/webjars/**").permitAll()
                        .pathMatchers("/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .pathMatchers("/api/v1/auth/refresh-token", "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password").permitAll()
                        .pathMatchers("/api/v1/auth/send-otp", "/api/v1/auth/verify-otp").permitAll()
                        .pathMatchers("/api/v1/brokers/callback").permitAll()
                        .pathMatchers("/api/v1/brokers/postback/**").permitAll()
                        .pathMatchers("/ws/**").permitAll()
                        .pathMatchers("/api/auth/**").permitAll()
                        .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyExchange().authenticated()
                )
                .addFilterAt(filter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
