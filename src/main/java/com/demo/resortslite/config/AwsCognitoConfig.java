package com.demo.resortslite.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

import javax.annotation.PostConstruct;

/**
 * AWS Cognito Configuration for User Authentication
 * 
 * FIXED cr-java-0090: Replaces file-based authentication with Amazon Cognito
 * for cloud-native user identity and access management.
 * 
 * Amazon Cognito provides:
 * - Centralized user identity management
 * - Encrypted credential storage
 * - Built-in user lifecycle management (registration, login, password reset)
 * - Multi-factor authentication (MFA) support
 * - OAuth 2.0 and OpenID Connect support
 * - Auditable authentication events
 * - Horizontal scalability for cloud environments
 * 
 * Configuration:
 * 1. Create a Cognito User Pool in AWS Console or via CloudFormation
 * 2. Create an App Client in the User Pool
 * 3. Set environment variables or Parameter Store values:
 *    - AWS_COGNITO_USER_POOL_ID: The User Pool ID (e.g., us-east-1_ABC123XYZ)
 *    - AWS_COGNITO_CLIENT_ID: The App Client ID
 *    - AWS_REGION: AWS region (e.g., us-east-1)
 * 
 * Usage:
 * - Inject CognitoIdentityProviderClient to authenticate users
 * - Use AdminInitiateAuth or InitiateAuth for user login
 * - Use SignUp for user registration
 * - Use ConfirmSignUp for email/SMS verification
 * - Use ForgotPassword and ConfirmForgotPassword for password reset
 */
@Configuration
public class AwsCognitoConfig {

    @Value("${aws.cognito.userPoolId:}")
    private String userPoolId;

    @Value("${aws.cognito.clientId:}")
    private String clientId;

    @Value("${aws.cognito.region:us-east-1}")
    private String awsRegion;

    @PostConstruct
    public void init() {
        if (userPoolId == null || userPoolId.isEmpty()) {
            System.err.println("WARNING: AWS Cognito User Pool ID is not configured.");
            System.err.println("Set AWS_COGNITO_USER_POOL_ID environment variable or aws.cognito.userPoolId property.");
            System.err.println("User authentication will fall back to local development mode.");
        }
        if (clientId == null || clientId.isEmpty()) {
            System.err.println("WARNING: AWS Cognito Client ID is not configured.");
            System.err.println("Set AWS_COGNITO_CLIENT_ID environment variable or aws.cognito.clientId property.");
        }
    }

    /**
     * Create AWS Cognito Identity Provider Client bean
     * Used for user authentication, registration, and management operations
     */
    @Bean
    public CognitoIdentityProviderClient cognitoIdentityProviderClient() {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    public String getUserPoolId() {
        return userPoolId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getAwsRegion() {
        return awsRegion;
    }

    /**
     * Check if Cognito is properly configured
     * Returns true if User Pool ID and Client ID are set
     */
    public boolean isCognitoConfigured() {
        return userPoolId != null && !userPoolId.isEmpty() 
            && clientId != null && !clientId.isEmpty();
    }
}
