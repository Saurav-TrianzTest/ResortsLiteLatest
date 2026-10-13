package com.demo.resortslite;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis Cache Configuration for Amazon ElastiCache
 * 
 * FIXED cr-java-0067: Migrated from unbounded in-memory HashMap to Amazon ElastiCache for Redis
 * with proper TTL policies. This ensures:
 * - Controlled cache expiration (30-minute TTL for booking cache)
 * - Consistent data across multiple EC2 instances
 * - Centralized cache management
 * - Horizontal scalability without cache synchronization issues
 * - Automatic memory management and eviction policies
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    /**
     * Configure Redis-backed cache manager with TTL policies
     * 
     * Cache names and TTL configuration:
     * - "bookingCache": 30 minutes TTL for booking data
     * - Default: 60 minutes TTL for other caches
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // Configure serialization for cache keys and values
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(60)) // Default TTL: 60 minutes
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer())
                )
                .disableCachingNullValues(); // Don't cache null values

        // Configure specific TTL for booking cache (30 minutes)
        RedisCacheConfiguration bookingCacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30)) // Booking cache TTL: 30 minutes
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer())
                )
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withCacheConfiguration("bookingCache", bookingCacheConfig)
                .transactionAware() // Enable transaction support
                .build();
    }
}
