package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * AWS Secrets Manager Configuration
 * Retrieves database credentials and other sensitive configuration from AWS Secrets Manager
 * instead of hard-coding them in source code or property files.
 */
@Configuration
public class AwsSecretsManagerConfig {

    @Value("${aws.secretsmanager.secret.name:resortslite/database/credentials}")
    private String secretName;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    private Map<String, String> secrets = new HashMap<>();
    private SecretsManagerClient secretsManagerClient;

    @PostConstruct
    public void init() {
        try {
            // Initialize AWS Secrets Manager client
            secretsManagerClient = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            // Retrieve secrets from AWS Secrets Manager
            retrieveSecrets();
        } catch (Exception e) {
            // Log error but don't fail application startup
            // In development, fall back to environment variables or default values
            System.err.println("Warning: Unable to retrieve secrets from AWS Secrets Manager: " + e.getMessage());
            System.err.println("Falling back to environment variables or default configuration");
        }
    }

    private void retrieveSecrets() {
        try {
            GetSecretValueRequest getSecretValueRequest = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();

            GetSecretValueResponse getSecretValueResponse = secretsManagerClient.getSecretValue(getSecretValueRequest);
            String secretString = getSecretValueResponse.secretString();

            // Parse JSON secret string
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode secretJson = objectMapper.readTree(secretString);

            // Extract individual secret values
            if (secretJson.has("dbHost")) {
                secrets.put("dbHost", secretJson.get("dbHost").asText());
            }
            if (secretJson.has("dbUser")) {
                secrets.put("dbUser", secretJson.get("dbUser").asText());
            }
            if (secretJson.has("dbPassword")) {
                secrets.put("dbPassword", secretJson.get("dbPassword").asText());
            }
            if (secretJson.has("paymentApiUrl")) {
                secrets.put("paymentApiUrl", secretJson.get("paymentApiUrl").asText());
            }
            // FIXED cr-java-0090: Retrieve confirmation code salt from Secrets Manager
            // Used for secure SHA-256 hashing of booking confirmation codes
            if (secretJson.has("confirmationCodeSalt")) {
                secrets.put("confirmationCodeSalt", secretJson.get("confirmationCodeSalt").asText());
            }

            System.out.println("Successfully retrieved secrets from AWS Secrets Manager");
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve secrets from AWS Secrets Manager", e);
        }
    }

    /**
     * Get database host from AWS Secrets Manager
     * Falls back to environment variable if Secrets Manager is unavailable
     */
    public String getDbHost() {
        return secrets.getOrDefault("dbHost", System.getenv().getOrDefault("DB_HOST", "localhost"));
    }

    /**
     * Get database username from AWS Secrets Manager
     * Falls back to environment variable if Secrets Manager is unavailable
     */
    public String getDbUser() {
        return secrets.getOrDefault("dbUser", System.getenv().getOrDefault("DB_USER", "sa"));
    }

    /**
     * Get database password from AWS Secrets Manager
     * Falls back to environment variable if Secrets Manager is unavailable
     */
    public String getDbPassword() {
        return secrets.getOrDefault("dbPassword", System.getenv().getOrDefault("DB_PASSWORD", ""));
    }

    /**
     * Get payment API URL from AWS Secrets Manager
     * Falls back to environment variable if Secrets Manager is unavailable
     */
    public String getPaymentApiUrl() {
        return secrets.getOrDefault("paymentApiUrl", System.getenv().getOrDefault("PAYMENT_API_URL", "http://localhost:9090/payments/charge"));
    }

    /**
     * Get confirmation code salt from AWS Secrets Manager
     * Falls back to environment variable if Secrets Manager is unavailable
     * Used for secure SHA-256 hashing of booking confirmation codes
     */
    public String getConfirmationCodeSalt() {
        return secrets.getOrDefault("confirmationCodeSalt", 
            System.getenv().getOrDefault("CONFIRMATION_CODE_SALT", ""));
    }

    /**
     * Refresh secrets from AWS Secrets Manager
     * Can be called periodically to support secret rotation
     */
    public void refreshSecrets() {
        if (secretsManagerClient != null) {
            retrieveSecrets();
        }
    }
}
