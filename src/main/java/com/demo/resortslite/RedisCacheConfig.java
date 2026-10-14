package com.demo.resortslite;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * cz-java-0070 FIX: Redis Cache Configuration
 *
 * Replaces the static in-process HashMap cache in BookingController with a
 * distributed cache backed by Amazon ElastiCache (Redis). This enables:
 *   - Shared cache state across all horizontally-scaled EKS pods
 *   - Cache entries survive container restarts and pod replacements
 *   - Consistent cache reads regardless of which pod handles the request
 *   - Configurable TTL enforced centrally in ElastiCache
 *
 * @EnableCaching activates Spring's cache abstraction so that
 * @Cacheable / @CachePut / @CacheEvict annotations in BookingController
 * are intercepted and routed to the RedisCacheManager defined here.
 *
 * Redis connection re-uses the same LettuceConnectionFactory configured in
 * RedisSessionConfig, which reads the ElastiCache endpoint from environment
 * variables:
 *   REDIS_HOST — ElastiCache primary endpoint (default: localhost)
 *   REDIS_PORT — ElastiCache port             (default: 6379)
 *
 * Cache TTL is set to 30 minutes (configurable via CACHE_TTL_MINUTES env var).
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    /**
     * Builds a RedisCacheManager that uses the shared RedisConnectionFactory
     * (backed by Amazon ElastiCache) and serialises cache values as JSON so
     * entries are human-readable and cross-JVM compatible.
     *
     * @param redisConnectionFactory the Lettuce connection factory wired to
     *                               ElastiCache via REDIS_HOST / REDIS_PORT
     * @return a fully configured RedisCacheManager
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory redisConnectionFactory) {
        long ttlMinutes = Long.parseLong(
                System.getenv().getOrDefault("CACHE_TTL_MINUTES", "30"));

        RedisCacheConfiguration cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(ttlMinutes))
                .disableCachingNullValues()
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer()));

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(cacheConfig)
                .build();
    }
}
