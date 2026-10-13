package com.demo.resortslite.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration class for AWS Systems Manager Parameter Store integration.
 * FIXED cr-java-0071: Externalizes environment-specific URLs to AWS Parameter Store
 * for cloud-native configuration management.
 */
@Configuration
public class AwsParameterStoreConfig {

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${aws.parameterstore.prefix:/resortslite}")
    private String parameterPrefix;

    private SsmClient ssmClient;
    private Map<String, String> parameterCache = new HashMap<>();

    @PostConstruct
    public void initializeSsmClient() {
        this.ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @PreDestroy
    public void closeSsmClient() {
        if (ssmClient != null) {
            ssmClient.close();
        }
    }

    @Bean
    public SsmClient ssmClient() {
        return this.ssmClient;
    }

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     * FIXED cr-java-0071: Centralized method for retrieving environment-specific configuration.
     *
     * @param parameterName The name of the parameter (without prefix)
     * @param defaultValue  The default value to return if parameter is not found
     * @return The parameter value from Parameter Store or the default value
     */
    public String getParameter(String parameterName, String defaultValue) {
        String fullParameterName = parameterPrefix + "/" + parameterName;

        // Check cache first to reduce API calls
        if (parameterCache.containsKey(fullParameterName)) {
            return parameterCache.get(fullParameterName);
        }

        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(fullParameterName)
                    .withDecryption(true) // Support for SecureString parameters
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            String value = response.parameter().value();

            // Cache the value
            parameterCache.put(fullParameterName, value);

            return value;
        } catch (SsmException e) {
            System.err.println("Failed to retrieve parameter '" + fullParameterName + "' from Parameter Store: " + e.getMessage());
            System.err.println("Using default value: " + defaultValue);
            return defaultValue;
        }
    }

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store without a default.
     * FIXED cr-java-0071: Centralized method for retrieving environment-specific configuration.
     *
     * @param parameterName The name of the parameter (without prefix)
     * @return The parameter value from Parameter Store
     * @throws RuntimeException if parameter is not found
     */
    public String getParameter(String parameterName) {
        String fullParameterName = parameterPrefix + "/" + parameterName;

        // Check cache first
        if (parameterCache.containsKey(fullParameterName)) {
            return parameterCache.get(fullParameterName);
        }

        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(fullParameterName)
                    .withDecryption(true)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            String value = response.parameter().value();

            // Cache the value
            parameterCache.put(fullParameterName, value);

            return value;
        } catch (SsmException e) {
            throw new RuntimeException("Failed to retrieve required parameter '" + fullParameterName + "' from Parameter Store", e);
        }
    }

    /**
     * Clears the parameter cache. Useful for testing or forcing a refresh.
     */
    public void clearCache() {
        parameterCache.clear();
    }
}
