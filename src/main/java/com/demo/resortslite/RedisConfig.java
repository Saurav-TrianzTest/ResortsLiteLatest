package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * cz-java-0070 — Local Caches: Redis Cache Configuration
 *
 * Provides a RedisTemplate<String, Object> bean used by BookingController to store
 * booking cache entries in Azure Cache for Redis instead of a local in-process HashMap.
 *
 * Keys are serialized as plain UTF-8 strings (StringRedisSerializer).
 * Values are serialized as JSON (GenericJackson2JsonRedisSerializer) so that booking
 * Map<String, Object> entries are human-readable in Redis and survive JVM restarts.
 *
 * Redis connection credentials are injected at runtime from Azure Key Vault
 * via the Secrets Store CSI Driver as Kubernetes environment variables:
 *   - SPRING_REDIS_HOST     : Azure Cache for Redis hostname
 *   - SPRING_REDIS_PORT     : Redis port (default 6380 for Azure TLS)
 *   - SPRING_REDIS_PASSWORD : Redis access key from Key Vault secret
 *   - SPRING_REDIS_SSL      : true (Azure Cache for Redis requires TLS)
 */
@Configuration
public class RedisConfig {

    /**
     * Configures a RedisTemplate with String keys and JSON-serialized Object values.
     * Used by BookingController to cache booking entries in Azure Cache for Redis.
     *
     * @param connectionFactory auto-configured by Spring Boot from spring.redis.* properties
     * @return RedisTemplate<String, Object> for distributed booking cache operations
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        // Serialize keys as plain strings for readability in Redis CLI / Azure Portal
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // Serialize values as JSON so booking Map<String, Object> entries are portable
        // across JVM restarts and readable without Java deserialization
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
