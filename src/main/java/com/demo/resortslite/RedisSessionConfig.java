package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Redis-backed HTTP Session configuration (cr-java-0065 fix).
 *
 * Replaces the default in-memory HttpSession store with Amazon ElastiCache for Redis
 * via Spring Session Data Redis. All session attributes (e.g., "lastBooking",
 * "guestName") are transparently stored in and retrieved from the shared Redis cluster,
 * enabling stateless application instances that can be freely scaled horizontally
 * behind an AWS ALB without sticky sessions or session-affinity requirements.
 *
 * Connection parameters are resolved from environment variables at runtime:
 *   REDIS_HOST  — ElastiCache primary endpoint hostname (default: localhost)
 *   REDIS_PORT  — ElastiCache port                      (default: 6379)
 *
 * In AWS deployments set these variables in the ECS task definition, EKS pod spec,
 * or Elastic Beanstalk environment properties pointing to the ElastiCache cluster.
 */
@Configuration
@EnableRedisHttpSession
public class RedisSessionConfig {

    /**
     * Creates a Lettuce-based Redis connection factory using environment-supplied
     * ElastiCache endpoint coordinates.  Falls back to localhost:6379 for local
     * development when the environment variables are absent.
     *
     * @return a configured {@link RedisConnectionFactory}
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        String redisHost = System.getenv("REDIS_HOST") != null
                ? System.getenv("REDIS_HOST") : "localhost";
        int redisPort = 6379;
        String redisPortEnv = System.getenv("REDIS_PORT");
        if (redisPortEnv != null && !redisPortEnv.isEmpty()) {
            try {
                redisPort = Integer.parseInt(redisPortEnv);
            } catch (NumberFormatException ignored) {
                // keep default 6379
            }
        }
        return new LettuceConnectionFactory(redisHost, redisPort);
    }
}
