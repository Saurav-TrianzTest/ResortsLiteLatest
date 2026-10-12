package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * Spring configuration that exposes an AWS Systems Manager (SSM) Parameter Store client bean.
 *
 * <p><b>cr-java-0071 remediation</b>: Centralises the {@link SsmClient} so that any
 * Spring-managed component can retrieve environment-specific URLs at runtime from
 * AWS SSM Parameter Store instead of embedding hard-coded hostnames in source code.
 *
 * <p>The AWS region is resolved from the {@code AWS_REGION} environment variable
 * (or the {@code cloud.aws.region.static} property), defaulting to {@code us-east-1}.
 *
 * <p>Authentication uses the standard AWS SDK default credential provider chain,
 * which automatically picks up IAM instance profiles, ECS task roles, or
 * environment-variable credentials — no hard-coded keys required.
 */
@Configuration
public class SsmParameterStoreConfig {

    @Value("${cloud.aws.region.static:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Creates a {@link SsmClient} configured for the target AWS region.
     * Credentials are resolved via the default AWS credential provider chain
     * (IAM role, environment variables, ~/.aws/credentials) — never hard-coded.
     *
     * @return a fully configured {@link SsmClient} bean
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
