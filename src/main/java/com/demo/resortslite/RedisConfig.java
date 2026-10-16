package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * cz-java-0070 FIX: Redis configuration for distributed cache using Amazon ElastiCache.
 *
 * Replaces the local in-memory HashMap cache (bookingCache) in BookingController with a
 * shared RedisTemplate-backed cache. All EKS pod instances connect to the same ElastiCache
 * Redis cluster, ensuring cache consistency across horizontal scaling events.
 *
 * Connection details (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD, REDIS_SSL) are injected via
 * Kubernetes ConfigMaps and Secrets, with IRSA-secured access to Amazon ElastiCache on EKS.
 * These are already configured in application.properties via environment variable placeholders.
 */
@Configuration
public class RedisConfig {

    /**
     * Configures a RedisTemplate with String keys and JSON-serialized Object values.
     * Used by BookingController to store and retrieve booking entries in ElastiCache (Redis),
     * replacing the local static HashMap that was not visible across EKS pod instances.
     *
     * @param connectionFactory auto-configured by Spring Boot using REDIS_HOST / REDIS_PORT env vars
     * @return RedisTemplate<String, Object> for distributed booking cache operations
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        // Use String serializer for keys (e.g., "booking:<bookingId>")
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // Use JSON serializer for values to support deserialization across pod instances
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
