package com.walletledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        MySQLContainer container =
                new MySQLContainer(DockerImageName.parse("mysql:8.4"));

        // Required for Flyway to create our append-only triggers
        // when MySQL binary logging is enabled.
        container.withCommand("--log-bin-trust-function-creators=ON");

        return container;
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7"))
                .withExposedPorts(6379);
    }
}