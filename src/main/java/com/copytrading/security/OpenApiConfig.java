package com.copytrading.security;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

import java.util.List;

@Configuration
public class OpenApiConfig implements WebFluxConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Bean
    public OpenAPI ascentraOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ascentra Trading Platform API")
                        .description("Master-Child Copy Trading System — 6 Brokers (Groww, Zerodha, Fyers, Upstox, Dhan, Angel One). "
                                + "Supports Equity & F&O. See FRONTEND-INTEGRATION-COMPLETE.md for detailed request/response examples.")
                        .version("2.0"))
                .servers(List.of(
                        new Server().url("/").description("Current"),
                        new Server().url("http://13.53.246.13:8081").description("EC2 Production")
                ))
                .addSecurityItem(new SecurityRequirement().addList("Bearer"))
                .components(new Components()
                        .addSecuritySchemes("Bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }

    /**
     * Replace generic Map<String,Object> schemas with a freeform object schema
     * so Swagger shows "object" instead of "additionalProp1, additionalProp2".
     */
    @Bean
    public OpenApiCustomizer mapSchemaCustomizer() {
        return openApi -> {
            if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
                openApi.getComponents().getSchemas().forEach((name, schema) -> {
                    // Remove the ugly additionalProperties rendering for Map types
                    if (schema.getAdditionalProperties() != null) {
                        schema.setAdditionalProperties(null);
                        schema.setType("object");
                        schema.setDescription("JSON object — see FRONTEND-INTEGRATION-COMPLETE.md for exact field names");
                    }
                });
            }
        };
    }
}
