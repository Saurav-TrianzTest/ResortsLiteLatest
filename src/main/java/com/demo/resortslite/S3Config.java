package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * AWS S3 configuration class.
 * Provides a Spring-managed {@link S3Client} bean that uses the AWS region
 * resolved from the environment variable {@code AWS_REGION} (or the
 * {@code cloud.aws.region.static} property), falling back to {@code us-east-1}.
 *
 * <p>Credentials are resolved automatically by the AWS Default Credential
 * Provider Chain (IAM role, environment variables, ~/.aws/credentials, etc.),
 * which is the recommended approach for cloud-native deployments on AWS.</p>
 */
@Configuration
public class S3Config {

    @Value("${cloud.aws.region.static:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Creates and exposes an {@link S3Client} bean configured for the target AWS region.
     *
     * @return a fully configured S3Client instance
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
