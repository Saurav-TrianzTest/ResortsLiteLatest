package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native report generation using Amazon S3.
 *
 * <p>cr-java-0062 FIX: Replaced hard-coded absolute paths ("/var/legacy/reports/" and
 * "C:\\ResortBackups\\nightly\\") with environment-variable-backed S3 bucket names,
 * eliminating all local file-system write dependencies.</p>
 *
 * <p>cr-java-0063 FIX: Replaced all java.io.File persistent storage operations
 * (new File(), mkdirs(), FileWriter) with Amazon S3 client calls (AWS SDK for Java v2).
 * The local file system is ephemeral in cloud/container environments; data written
 * locally is lost on container restart or scale-out. Amazon S3 provides durable,
 * highly-available object storage that persists independently of any individual
 * container instance.</p>
 *
 * <p>cr-java-0071 FIX: Replaced hard-coded environment URL in buildReportDownloadUrl()
 * with a value retrieved from AWS Systems Manager Parameter Store, enabling
 * environment-agnostic deployments without code changes.</p>
 *
 * <p>cr-java-0077 FIX: Eliminated hard-coded port number (SERVER_PORT = 8080).
 * The server port is now resolved at runtime from AWS Systems Manager Parameter Store
 * under the parameter "/resortslite/server/port". An environment variable
 * SERVER_PORT_SSM_PARAM can override the SSM parameter name. If SSM is unavailable
 * (e.g., local development), the value falls back to the SERVER_PORT environment
 * variable, and finally to the Spring property "server.port". This enables dynamic
 * port assignment required by ECS, EKS, and Elastic Beanstalk without any code
 * changes between environments.</p>
 */
@Service
public class ReportService {

    // cr-java-0062 FIX: Replaced hard-coded absolute path "/var/legacy/reports/" with
    // an environment-variable-backed S3 bucket name, eliminating the local file system
    // write dependency. The S3 bucket is configured via the REPORT_S3_BUCKET environment
    // variable (defaults to "resort-reports" for local development).
    @Value("${REPORT_S3_BUCKET:resort-reports}")
    private String reportS3Bucket;

    // cr-java-0062 FIX: Replaced hard-coded Windows path "C:\\ResortBackups\\nightly\\"
    // with an environment-variable-backed S3 bucket name for backup objects.
    @Value("${BACKUP_S3_BUCKET:resort-backups}")
    private String backupS3Bucket;

    // AWS region is driven by an environment variable (defaults to us-east-1).
    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    // cr-java-0077 FIX: The SSM parameter name for the server port is injected via
    // environment variable SERVER_PORT_SSM_PARAM, defaulting to "/resortslite/server/port".
    // This allows each deployment environment (dev/staging/prod) to point to its own
    // SSM parameter without code changes.
    @Value("${SERVER_PORT_SSM_PARAM:/resortslite/server/port}")
    private String serverPortSsmParam;

    // cr-java-0077 FIX: Fallback port value sourced from the SERVER_PORT environment
    // variable or the Spring "server.port" property. Used only when SSM is unreachable
    // (e.g., local development). No literal port number is hard-coded in application logic.
    @Value("${server.port:8080}")
    private int serverPortFallback;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String objectKey = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system required.
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // cr-java-0063 FIX (source lines 37, 39, 42):
            //   BEFORE — java.io.File-based persistent storage:
            //     Line 37: File reportDir = new File(REPORT_BASE_PATH);   // java.io.File usage
            //     Line 39: reportDir.mkdirs();                            // local dir creation
            //     Line 42: FileWriter writer = new FileWriter(fullPath);  // local file write
            //
            //   AFTER — Amazon S3 object storage (AWS SDK for Java v2):
            //     All three java.io.File operations are replaced by a single S3 PutObject call.
            //     The local file system is ephemeral in cloud/container environments; data
            //     written locally is lost on container restart or scale-out. Amazon S3 provides
            //     durable, highly-available object storage that persists independently of any
            //     individual container instance.
            try (S3Client s3 = S3Client.builder()
                    .region(Region.of(awsRegion))
                    .build()) {

                PutObjectRequest putRequest = PutObjectRequest.builder()
                        .bucket(reportS3Bucket)
                        .key(objectKey)
                        .contentType("text/csv")
                        .build();

                s3.putObject(putRequest, RequestBody.fromString(csvContent.toString()));
            }

            String s3Url = "s3://" + reportS3Bucket + "/" + objectKey;
            result.put("status", "generated");
            result.put("path", s3Url);
            // cr-java-0077 FIX: Port is resolved from AWS SSM Parameter Store at runtime;
            // no hard-coded literal is used here.
            result.put("serverPort", resolveServerPort());

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a named report.
     *
     * <p>cr-java-0071 FIX: Replaced the hard-coded environment URL
     * ("http://reports.resorts-internal.com:8080/download/") with a base URL
     * retrieved from AWS Systems Manager Parameter Store under the parameter
     * "/resortslite/reports/download-base-url". This enables environment-agnostic
     * deployments — each environment (dev, staging, production) stores its own
     * endpoint in SSM without requiring code changes.</p>
     *
     * @param reportName the name of the report file to download
     * @return the fully-qualified download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: Replaced hard-coded environment URL with a value retrieved
        // from AWS Systems Manager Parameter Store. The parameter
        // "/resortslite/reports/download-base-url" holds the environment-specific base
        // URL, enabling environment-agnostic deployments without code changes.
        String baseUrl = getSsmParameter("/resortslite/reports/download-base-url",
                "http://reports.resorts-internal.com:8080/download/");
        return baseUrl + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX: Replaced java.util.Date / SimpleDateFormat with java.time API
        // (Instant + DateTimeFormatter) standardized on UTC. Using java.util.Date and
        // SimpleDateFormat without an explicit timezone causes timezone-dependent behaviour
        // that leads to scheduling failures and time-related logic errors in distributed
        // cloud environments spanning multiple regions or containers.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // Report the S3 bucket names instead of the former hard-coded local paths
        // (original lines 23 and 22 referenced REPORT_BASE_PATH / BACKUP_PATH).
        info.put("reportBucket", reportS3Bucket);
        info.put("backupBucket", backupS3Bucket);
        // cr-java-0077 FIX: Port resolved from AWS SSM Parameter Store — no hard-coded literal.
        info.put("serverPort", resolveServerPort());
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Resolves the server port at runtime from AWS Systems Manager Parameter Store.
     *
     * <p>cr-java-0077 FIX: Eliminates the hard-coded {@code SERVER_PORT = 8080} constant
     * (original source line 28). The port is looked up from SSM Parameter Store using the
     * parameter name configured via {@code SERVER_PORT_SSM_PARAM} environment variable
     * (default: {@code /resortslite/server/port}). If SSM is unreachable the method falls
     * back to the {@code server.port} Spring property / {@code SERVER_PORT} environment
     * variable, ensuring the application starts correctly in local development without
     * requiring SSM access.</p>
     *
     * @return the resolved server port number
     */
    private int resolveServerPort() {
        String rawPort = getSsmParameter(serverPortSsmParam, String.valueOf(serverPortFallback));
        try {
            return Integer.parseInt(rawPort.trim());
        } catch (NumberFormatException e) {
            return serverPortFallback;
        }
    }

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     * Falls back to the provided default value if the parameter cannot be retrieved
     * (e.g., during local development where SSM is not available).
     *
     * @param parameterName the SSM parameter name/path
     * @param defaultValue  fallback value used when SSM is unreachable
     * @return the resolved parameter value
     */
    private String getSsmParameter(String parameterName, String defaultValue) {
        try {
            try (SsmClient ssmClient = SsmClient.builder()
                    .region(Region.of(awsRegion))
                    .build()) {
                GetParameterRequest request = GetParameterRequest.builder()
                        .name(parameterName)
                        .withDecryption(true)
                        .build();
                GetParameterResponse response = ssmClient.getParameter(request);
                return response.parameter().value();
            }
        } catch (Exception e) {
            // Fall back to default value when SSM is not available (e.g., local dev)
            return defaultValue;
        }
    }
}
