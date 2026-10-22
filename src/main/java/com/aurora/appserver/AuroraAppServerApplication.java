package com.aurora.appserver;

import com.aurora.appserver.auth.config.AuroraAuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AuroraAuthProperties.class)
public class AuroraAppServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuroraAppServerApplication.class, args);
    }
}
