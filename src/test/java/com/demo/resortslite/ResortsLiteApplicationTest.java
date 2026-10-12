package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Tests for ResortsLiteApplication — verifies Spring context loads successfully.
 */
@SpringBootTest
@ActiveProfiles("test")
class ResortsLiteApplicationTest {

    /**
     * Verifies that the Spring application context loads without errors.
     * This is the primary smoke test for the entire application.
     */
    @Test
    void contextLoads() {
        // If the context fails to load, this test will fail automatically.
    }

    /**
     * Verifies that the main() entry point can be invoked without throwing exceptions.
     * Uses an empty args array to simulate a no-argument startup.
     */
    @Test
    void main_withEmptyArgs_doesNotThrow() {
        assertDoesNotThrow(() -> ResortsLiteApplication.main(new String[]{}));
    }
}
