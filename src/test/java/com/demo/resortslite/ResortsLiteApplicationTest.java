package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration / smoke tests for ResortsLiteApplication.
 * Uses H2 in-memory database (configured via application.properties for test profile).
 */
@SpringBootTest
@ActiveProfiles("test")
class ResortsLiteApplicationTest {

    @Test
    void contextLoads() {
        // Verifies that the Spring application context starts without errors.
        // This is the primary smoke test for the application.
    }

    @Test
    void main_doesNotThrowException() {
        // Arrange & Act & Assert
        assertDoesNotThrow(() ->
                ResortsLiteApplication.main(new String[]{"--spring.main.web-application-type=none"})
        );
    }
}
