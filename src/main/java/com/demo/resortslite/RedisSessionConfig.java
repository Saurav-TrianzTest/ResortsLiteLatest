package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cz-java-0063: Redis Session Configuration
 *
 * Replaces in-memory HttpSession with Spring Session backed by Amazon ElastiCache (Redis).
 * This enables stateless horizontal scaling across multiple EKS pod instances — all pods
 * share the same Redis session store, eliminating sticky-session requirements.
 *
 * Deployed as a Kubernetes workload on EKS with IRSA for secure access to ElastiCache.
 * Redis connection details are supplied via environment variables (no hardcoded values).
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800) // cz-java-0063: 30-minute session TTL in Redis
public class RedisSessionConfig {

    // cz-java-0063: Redis host sourced from environment variable — points to Amazon ElastiCache endpoint
    @Value("${REDIS_HOST:localhost}")
    private String redisHost;

    // cz-java-0063: Redis port sourced from environment variable
    @Value("${REDIS_PORT:6379}")
    private int redisPort;

    // cz-java-0063: Redis password sourced from environment variable (AWS Secrets Manager / EKS secret)
    @Value("${REDIS_PASSWORD:}")
    private String redisPassword;

    /**
     * cz-java-0063: Lettuce connection factory pointing to Amazon ElastiCache (Redis).
     * Connection parameters are injected via environment variables for container portability.
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(redisHost);
        config.setPort(redisPort);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            config.setPassword(redisPassword);
        }
        return new LettuceConnectionFactory(config);
    }

    /**
     * cz-java-0070: General-purpose RedisTemplate for distributed booking cache.
     * Uses String keys and JSON-serialised values so entries are human-readable in
     * Redis and portable across JVM restarts (no Java serialisation dependency).
     * Backed by the same Amazon ElastiCache (Redis) cluster as Spring Session.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(LettuceConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
