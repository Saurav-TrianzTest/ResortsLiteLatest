package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Cloud-native: S3 bucket name injected from environment variable / application properties
    // Replaces hardcoded absolute path "/var/legacy/reports/" (cr-java-0061, line 23)
    @Value("${cloud.aws.s3.bucket-name}")
    private String s3BucketName;

    // Cloud-native: S3 key prefix injected from environment variable / application properties
    // Replaces hardcoded Windows-style backup path "C:\\ResortBackups\\nightly\\" (cr-java-0061)
    @Value("${cloud.aws.s3.report-prefix:reports/}")
    private String reportPrefix;

    // Cloud-native: S3 key prefix for backup objects
    // Replaces hardcoded backup path dependency on local OS directory structure
    @Value("${cloud.aws.s3.backup-prefix:backups/nightly/}")
    private String backupPrefix;

    // cr-java-0077 FIX: Hard-coded port (SERVER_PORT = 8080) replaced with environment variable injection.
    // The port is now resolved at runtime from the SERVER_PORT environment variable (ECS task definition /
    // EKS pod spec / Elastic Beanstalk environment property), falling back to the Spring server.port
    // property which is itself driven by the SERVER_PORT env var in application.properties.
    // AWS Parameter Store parameter: /resortslite/server/port
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0071 FIX: Report download base URL externalized via AWS Systems Manager Parameter Store.
    // The value is resolved at startup from the SSM parameter /resortslite/reports/download-base-url
    // (configured in application.properties as app.reports.download-base-url).
    // Replaces the hard-coded "http://reports.resorts-internal.com:8080/download/" URL that was
    // present in the original source (line 66 of the original ReportService.java).
    // Set the environment variable APP_REPORTS_DOWNLOAD_BASE_URL or configure the SSM parameter
    // /resortslite/reports/download-base-url to override the default for each deployment environment.
    @Value("${app.reports.download-base-url:https://reports.resorts-internal.com/download/}")
    private String reportDownloadBaseUrl;

    private final S3Client s3Client;

    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Generates a monthly report CSV and uploads it to Amazon S3.
     * Replaces local file-system write operations that used hardcoded absolute paths
     * ("/var/legacy/reports/") which do not exist in cloud/container environments.
     *
     * @param month the month for the report (e.g. "03")
     * @param year  the year for the report (e.g. "2024")
     * @return a map containing the operation status and the S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // Cloud-native: S3 object key replaces hardcoded local fullPath (cr-java-0061, line 37)
        String s3Key = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local File/FileWriter needed (cr-java-0061, line 42)
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes();

            // Upload report CSV directly to Amazon S3
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(contentBytes));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            result.put("serverPort", serverPort);

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.awsErrorDetails().errorMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for a report using the environment-specific base URL
     * retrieved from AWS Systems Manager Parameter Store.
     *
     * cr-java-0071 FIX: The report download base URL is no longer hard-coded.
     * It is injected from AWS SSM Parameter Store via Spring's @Value binding
     * (property key: app.reports.download-base-url). The SSM parameter
     * /resortslite/reports/download-base-url holds the environment-specific endpoint,
     * allowing the same artifact to be deployed across dev, staging, and production
     * without any code changes.
     *
     * @param reportName the name of the report object
     * @return a URL pointing to the report using the externalized base URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: reportDownloadBaseUrl is injected from SSM Parameter Store
        // via app.reports.download-base-url — no hard-coded environment-specific URL.
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information using cloud-native S3 storage references
     * instead of local file system paths.
     *
     * @return a map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX: Replaced java.util.Date / SimpleDateFormat (timezone-sensitive legacy API)
        // with java.time.Instant (UTC) formatted via DateTimeFormatter.
        // Standardized on UTC (ZoneOffset.UTC) to ensure consistent timestamps across all cloud
        // regions, containers, and distributed service instances — eliminates timezone drift issues.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // Cloud-native: S3 bucket/prefix references replace hardcoded local paths (cr-java-0061)
        info.put("reportBucket", s3BucketName);
        info.put("reportPrefix", reportPrefix);
        info.put("backupPrefix", backupPrefix);
        info.put("serverPort", serverPort);
        info.put("reportDownloadBaseUrl", reportDownloadBaseUrl);
        info.put("generatedAt", timestamp);
        return info;
    }
}
