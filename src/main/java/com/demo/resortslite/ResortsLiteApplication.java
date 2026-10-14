package com.demo.resortslite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

// FIX cz-java-0063 [Server-side Sessions]: @EnableRedisHttpSession activates Spring Session
// so that all HttpSession operations are transparently backed by Azure Cache for Redis.
// This ensures session data is shared across all horizontally-scaled container instances on AKS.
// Redis connection details (REDIS_HOST, REDIS_PASSWORD) are injected via Azure Key Vault
// Secrets Store CSI Driver — no credentials are hardcoded in the application.
@SpringBootApplication
@EnableRedisHttpSession
public class ResortsLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResortsLiteApplication.class, args);
    }
}
