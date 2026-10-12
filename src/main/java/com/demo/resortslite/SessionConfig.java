package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cz-java-0063 FIX: Spring Session Redis Configuration.
 *
 * Replaces in-memory HttpSession with Spring Session backed by Amazon ElastiCache for Redis.
 * All session data is stored externally in Redis, enabling:
 *   - Horizontal scaling across multiple EKS pod replicas
 *   - Session persistence across container restarts
 *   - Consistent session state regardless of which pod handles the request
 *
 * cz-java-0070 FIX: Provides a RedisTemplate<String, Object> bean used by BookingController
 * to store and retrieve booking cache entries in Amazon ElastiCache (Redis), replacing the
 * previous instance-local HashMap cache that was invisible to other EKS pod replicas.
 *
 * Redis connection is configured via environment variables (REDIS_HOST, REDIS_PORT)
 * injected through Kubernetes ConfigMap / Secrets in the EKS Pod spec.
 * IRSA (IAM Roles for Service Accounts) is used for secure access to ElastiCache.
 */
@Configuration
@EnableRedisHttpSession
public class SessionConfig {

    /**
     * Creates a Redis connection factory using environment variables for host and port.
     * REDIS_HOST and REDIS_PORT are injected via Kubernetes ConfigMap or environment
     * variables pointing to the Amazon ElastiCache Redis cluster endpoint.
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);

        // If REDIS_PASSWORD is set (e.g., ElastiCache AUTH token), apply it
        String redisPassword = System.getenv("REDIS_PASSWORD");
        if (redisPassword != null && !redisPassword.isEmpty()) {
            config.setPassword(redisPassword);
        }

        return new LettuceConnectionFactory(config);
    }

    /**
     * cz-java-0070 FIX: RedisTemplate for distributed cache operations.
     *
     * Provides a type-safe RedisTemplate<String, Object> used by BookingController to
     * store and retrieve booking entries in Amazon ElastiCache (Redis). This replaces
     * the previous instance-local HashMap (bookingCache) that was not visible to other
     * EKS pod replicas, breaking horizontal scaling.
     *
     * - Keys are serialised as plain UTF-8 strings (e.g., "booking:<bookingId>").
     * - Values are serialised as JSON via GenericJackson2JsonRedisSerializer, ensuring
     *   portability across JVM restarts and different pod instances.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}
