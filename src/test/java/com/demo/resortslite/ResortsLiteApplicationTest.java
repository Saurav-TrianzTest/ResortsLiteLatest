package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Tests for ResortsLiteApplication – verifies Spring context loads successfully.
 */
@SpringBootTest
@ActiveProfiles("test")
class ResortsLiteApplicationTest {

    /**
     * Verifies that the Spring application context loads without errors.
     */
    @Test
    void contextLoads() {
        // If the context fails to load, Spring Boot will throw an exception
        // and this test will fail automatically.
    }

    /**
     * Verifies that the main() entry-point can be invoked without throwing.
     * Uses the test profile to avoid connecting to Oracle DB.
     */
    @Test
    void main_doesNotThrow() {
        // Set the active profile to "test" so H2 is used instead of Oracle
        System.setProperty("spring.profiles.active", "test");
        try {
            assertDoesNotThrow(() -> ResortsLiteApplication.main(new String[]{"--spring.profiles.active=test"}));
        } finally {
            System.clearProperty("spring.profiles.active");
        }
    }
}
