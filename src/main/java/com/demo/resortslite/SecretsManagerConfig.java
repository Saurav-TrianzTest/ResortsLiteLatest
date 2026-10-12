package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Spring configuration that exposes AWS Secrets Manager and Amazon Cognito client beans.
 *
 * <p><b>cr-java-0069 remediation</b>: centralises the {@link SecretsManagerClient} so that
 * any Spring-managed component (e.g. {@link BookingService}) can retrieve database credentials
 * at runtime instead of embedding them in source code.</p>
 *
 * <p><b>cr-java-0090 remediation (File-based Authentication)</b>: exposes a
 * {@link CognitoIdentityProviderClient} bean so that {@link BookingService} can delegate
 * user identity management to Amazon Cognito User Pools. This replaces any file-based user
 * store or locally-stored authentication data with a centralised, encrypted, and auditable
 * cloud-native identity service. User attributes (email, name, roles) are retrieved directly
 * from Cognito — no local files, no hardcoded credentials, no file I/O.</p>
 *
 * <p>The AWS region is resolved from the {@code AWS_REGION} environment variable
 * (or the {@code cloud.aws.region.static} property already present in
 * {@code application.properties}), defaulting to {@code us-east-1}.</p>
 *
 * <p>Authentication uses the standard AWS SDK default credential provider chain,
 * which automatically picks up IAM instance profiles, ECS task roles, or
 * environment-variable credentials — no hard-coded keys required.</p>
 */
@Configuration
public class SecretsManagerConfig {

    @Value("${cloud.aws.region.static:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Creates a {@link SecretsManagerClient} configured for the target AWS region.
     * Credentials are resolved via the default AWS credential provider chain
     * (IAM role, environment variables, ~/.aws/credentials) — never hard-coded.
     *
     * @return a fully configured {@link SecretsManagerClient} bean
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Creates a {@link CognitoIdentityProviderClient} configured for the target AWS region.
     *
     * <p><b>cr-java-0090 FIX</b>: This bean enables {@link BookingService} to perform
     * cloud-native user identity lookups against Amazon Cognito User Pools, replacing
     * any file-based authentication or locally-stored user data. Credentials are resolved
     * via the default AWS credential provider chain — no hard-coded keys required.</p>
     *
     * <p>Configure the target User Pool via the {@code COGNITO_USER_POOL_ID} environment
     * variable (or {@code app.cognito.user-pool-id} property) in the ECS task definition,
     * EKS pod spec, or Elastic Beanstalk environment configuration.</p>
     *
     * @return a fully configured {@link CognitoIdentityProviderClient} bean
     */
    @Bean
    public CognitoIdentityProviderClient cognitoIdentityProviderClient() {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
