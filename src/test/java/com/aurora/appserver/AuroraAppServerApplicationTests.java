package com.aurora.appserver;

import com.aurora.appserver.support.ExternalMySqlSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
class AuroraAppServerApplicationTests {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        ExternalMySqlSupport.registerDatasource(registry);
    }

    @Test
    void contextLoads() {
        // Verifies Spring Boot application context starts with Flyway + JPA validate.
    }
}
