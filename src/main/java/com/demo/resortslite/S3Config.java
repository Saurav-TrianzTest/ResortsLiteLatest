package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Spring configuration class that provides an AWS S3Client bean.
 *
 * <p>The S3Client is used by {@link ReportService} to replace hard-coded local file
 * system paths (/var/legacy/reports/ and C:\ResortBackups\nightly\) with cloud-native
 * Amazon S3 object storage operations (cr-java-0061).</p>
 *
 * <p>The AWS region is read from the {@code aws.region} property which defaults to the
 * {@code AWS_REGION} environment variable, making it fully portable across cloud
 * environments without any hard-coded values.</p>
 */
@Configuration
public class S3Config {

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Creates and configures an AWS SDK v2 S3Client bean.
     * Credentials are resolved automatically via the AWS Default Credential Provider Chain
     * (IAM role, environment variables, ~/.aws/credentials), which is the recommended
     * cloud-native approach for AWS deployments.
     *
     * @return configured {@link S3Client} instance
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
