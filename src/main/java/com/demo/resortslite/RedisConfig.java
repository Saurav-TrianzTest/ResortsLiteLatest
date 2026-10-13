package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * cz-java-0070: Redis configuration for Amazon ElastiCache (Redis) cache backend.
 *
 * Configures a RedisTemplate<String, Object> bean with JSON serialization so that
 * booking objects can be stored and retrieved from the shared ElastiCache cluster.
 * Connection details (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD) are injected via
 * Kubernetes ConfigMaps and Secrets through environment variables, enabling
 * IRSA-secured access to the ElastiCache cluster on EKS.
 *
 * This replaces the local in-memory HashMap cache in BookingController that was
 * instance-local and invisible to other EKS pod replicas during horizontal scaling.
 */
@Configuration
public class RedisConfig {

    /**
     * cz-java-0070: Provides a RedisTemplate configured with String key serializer
     * and JSON value serializer for storing booking objects in Amazon ElastiCache (Redis).
     * The RedisConnectionFactory is auto-configured by Spring Boot using the
     * REDIS_HOST, REDIS_PORT, and REDIS_PASSWORD environment variables.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        // Use String serializer for keys for human-readable key names in ElastiCache
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // Use JSON serializer for values to support arbitrary Object types (e.g., booking maps)
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
