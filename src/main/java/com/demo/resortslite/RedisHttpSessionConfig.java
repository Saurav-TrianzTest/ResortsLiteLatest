package com.demo.resortslite;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cz-java-0063 — Server-side Sessions: Redis HTTP Session Configuration
 *
 * Replaces the default in-memory HttpSession with Spring Session backed by
 * Azure Cache for Redis. This ensures session state is stored externally and
 * shared across all AKS pod replicas, enabling stateless horizontal scaling
 * without sticky sessions.
 *
 * Redis connection credentials are injected at runtime from Azure Key Vault
 * via the Secrets Store CSI Driver as Kubernetes environment variables:
 *   - SPRING_REDIS_HOST     : Azure Cache for Redis hostname
 *   - SPRING_REDIS_PORT     : Redis port (default 6380 for Azure TLS)
 *   - SPRING_REDIS_PASSWORD : Redis access key from Key Vault secret
 *   - SPRING_REDIS_SSL      : true (Azure Cache for Redis requires TLS)
 *
 * maxInactiveIntervalInSeconds controls session TTL in Redis (default: 1800s).
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisHttpSessionConfig {
    // Spring Session auto-configures the Redis connection using
    // spring.redis.* properties resolved from environment variables.
    // No additional bean definitions are required when using
    // spring-boot-starter-data-redis with Spring Boot auto-configuration.
}
