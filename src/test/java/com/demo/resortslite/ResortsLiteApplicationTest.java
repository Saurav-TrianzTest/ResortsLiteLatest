package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@SpringBootTest
@ActiveProfiles("test")
class ResortsLiteApplicationTest {

    @Test
    void contextLoads() {
        // Verifies that the Spring application context starts without errors
    }

    @Test
    void main_doesNotThrowException() {
        // Arrange / Act / Assert
        assertDoesNotThrow(() ->
                ResortsLiteApplication.main(new String[]{"--spring.main.web-application-type=none"}),
                "Application main() should not throw an exception"
        );
    }
}
