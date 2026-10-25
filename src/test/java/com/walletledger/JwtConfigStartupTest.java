package com.walletledger;

import com.walletledger.config.JwtConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtConfigStartupTest {

    @Test
    void contextFailsToStartWhenJwtSecretIsInvalid() {
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext();

        try {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(new MapPropertySource(
                            "testJwtProperties",
                            Map.of(
                                    "walletledger.security.jwt.secret",
                                    "not-valid-base64!"
                            )
                    ));

            context.register(JwtConfig.class);

            assertThrows(RuntimeException.class, context::refresh);
        } finally {
            context.close();
        }
    }

    @Test
    void contextFailsToStartWhenJwtSecretIsMissing() {
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext();

        try {
            context.register(JwtConfig.class);

            assertThrows(RuntimeException.class, context::refresh);
        } finally {
            context.close();
        }
    }
}