package com.demo.resortslite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

// cz-java-0063: @EnableRedisHttpSession activates Spring Session with Amazon ElastiCache (Redis)
// as the backing store. All HttpSession usage in controllers is transparently redirected to Redis,
// making sessions durable across container restarts and consistent across all EKS pod replicas.
@SpringBootApplication
@EnableRedisHttpSession
public class ResortsLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResortsLiteApplication.class, args);
    }
}
