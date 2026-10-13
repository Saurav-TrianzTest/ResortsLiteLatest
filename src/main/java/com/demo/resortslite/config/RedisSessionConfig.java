package com.demo.resortslite.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Redis Session Configuration for Amazon ElastiCache
 * 
 * FIXED cr-java-0065: Migrates HTTP session state from in-memory storage to 
 * Amazon ElastiCache for Redis, enabling stateless application instances with 
 * centralized, distributed session management.
 * 
 * This configuration:
 * - Enables Spring Session with Redis as the backing store
 * - Allows horizontal scaling without session affinity
 * - Provides session persistence across instance restarts
 * - Supports AWS ElastiCache for Redis in production
 * 
 * Configuration properties:
 * - REDIS_HOST: ElastiCache cluster endpoint (e.g., resortslite-session.abc123.ng.0001.use1.cache.amazonaws.com)
 * - REDIS_PORT: Redis port (default: 6379)
 * - REDIS_PASSWORD: Redis password (if authentication is enabled)
 * - REDIS_SSL: Enable SSL/TLS for Redis connections (recommended for production)
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800) // 30 minutes session timeout
public class RedisSessionConfig {

    /**
     * Redis connection factory bean.
     * Spring Boot auto-configuration will create this bean based on application.properties,
     * but we can customize it here if needed for AWS ElastiCache specific settings.
     * 
     * For production AWS ElastiCache:
     * - Use cluster mode for high availability
     * - Enable SSL/TLS encryption in transit
     * - Configure connection pooling for optimal performance
     * - Set appropriate timeouts for cloud network latency
     */
    // Note: Spring Boot auto-configuration handles RedisConnectionFactory creation
    // based on spring.redis.* properties in application.properties
    // Uncomment and customize below if you need AWS-specific Redis configuration
    
    /*
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // Custom configuration for AWS ElastiCache if needed
        LettuceConnectionFactory factory = new LettuceConnectionFactory();
        // Configure SSL, timeouts, pool settings, etc.
        return factory;
    }
    */
}
