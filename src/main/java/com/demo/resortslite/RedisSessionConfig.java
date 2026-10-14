package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cz-java-0063 FIX: Redis-backed HTTP Session Configuration
 *
 * Replaces the default in-memory HttpSession store with Amazon ElastiCache (Redis)
 * via Spring Session Data Redis. This enables:
 *  - Stateless, horizontally-scalable EKS pods (no sticky sessions)
 *  - Session persistence across container restarts and pod replacements
 *  - Shared session state across all instances in the cluster
 *
 * Redis connection is configured via environment variables:
 *   REDIS_HOST  — ElastiCache primary endpoint (default: localhost)
 *   REDIS_PORT  — ElastiCache port             (default: 6379)
 *
 * maxInactiveIntervalInSeconds: 1800 (30 minutes) — session TTL in Redis.
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        return new LettuceConnectionFactory(redisHost, redisPort);
    }
}
