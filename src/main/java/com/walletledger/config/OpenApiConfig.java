package com.walletledger.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String USER_ID_SCHEME = "UserIdHeader";

    @Bean
    public OpenAPI walletLedgerOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("WalletLedger API")
                        .version("v1")
                        .description(
                                "WalletLedger REST API. "
                                + "Development identity uses the X-User-Id header."
                        ))
                .components(new Components()
                        .addSecuritySchemes(
                                USER_ID_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-User-Id")
                                        .description(
                                                "Development only: enter a UUID."
                                        )
                        ));
    }
}
