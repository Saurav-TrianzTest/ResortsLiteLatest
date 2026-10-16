package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Spring configuration class that provides an Amazon S3 client bean.
 *
 * <p>The S3Client is configured using the AWS region sourced from the
 * {@code cloud.aws.region} application property (backed by the {@code AWS_REGION}
 * environment variable). Credentials are resolved automatically by the AWS SDK
 * default credential provider chain (IAM role, environment variables, or
 * ~/.aws/credentials), following cloud-native best practices.</p>
 *
 * <p>This bean replaces all hardcoded local file-system path dependencies
 * (cr-java-0061) in {@link ReportService} with Amazon S3 object storage.</p>
 */
@Configuration
public class S3Config {

    @Value("${cloud.aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Creates and configures an {@link S3Client} for the application.
     *
     * <p>Credentials are resolved via the AWS default credential provider chain:
     * <ol>
     *   <li>Environment variables: {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY}</li>
     *   <li>AWS IAM role attached to the EC2 instance, ECS task, or Lambda function</li>
     *   <li>AWS profile in {@code ~/.aws/credentials}</li>
     * </ol>
     * </p>
     *
     * @return a configured {@link S3Client} instance
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
