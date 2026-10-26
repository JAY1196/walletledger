package com.walletledger.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "BearerAuth";

    @Bean
    public OpenAPI walletLedgerOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("WalletLedger API")
                        .version("v1")
                        .description(
                                "WalletLedger REST API. "
                                + "Protected endpoints require a valid JWT "
                                + "in the Authorization Bearer header."
                        ))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description(
                                                "Enter the access token returned "
                                                + "by POST /api/v1/auth/login."
                                        )
                        ));
    }
}