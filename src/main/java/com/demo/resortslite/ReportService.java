package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — generates resort reports and persists them to Amazon S3.
 *
 * <p>Fix cr-java-0062 (Local File System Write Operations):
 * All local {@link java.io.FileWriter} / {@link java.io.File} operations that previously
 * wrote to ephemeral container paths (/var/legacy/reports/ and C:\ResortBackups\nightly\)
 * have been replaced with Amazon S3 {@link PutObjectRequest} calls so that report data
 * survives container restarts, auto-scaling events, and node replacements.</p>
 *
 * <p>Fix cr-java-0071 (Hard-coded Environment URLs):
 * The previously hard-coded report download URL
 * {@code "http://reports.resorts-internal.com:8080/download/"} has been replaced with a
 * value retrieved at runtime from AWS Systems Manager Parameter Store, enabling
 * environment-agnostic deployments without code changes.</p>
 */
@Service
public class ReportService {

    // Replaced hard-coded absolute path "/var/legacy/reports/" (cr-java-0061 / cr-java-0062)
    // with an environment-variable-backed S3 bucket name for cloud-native object storage.
    @Value("${cloud.aws.s3.bucket-name}")
    private String s3BucketName;

    // Replaced hard-coded Windows-style backup path "C:\\ResortBackups\\nightly\\" (cr-java-0061 / cr-java-0062)
    // with an environment-variable-backed S3 key prefix for backup objects.
    @Value("${cloud.aws.s3.backup-prefix:backups/nightly/}")
    private String s3BackupPrefix;

    // cr-java-0077 FIX: Hard-coded port 8080 replaced with a value injected from the
    // SERVER_PORT environment variable (set by ECS task definition / EKS pod spec /
    // Elastic Beanstalk environment properties).  Falls back to 8080 only for local
    // development when the variable is not set.
    // The canonical port configuration lives in application.properties as:
    //   server.port=${SERVER_PORT:8080}
    // so Spring Boot itself also honours the same environment variable.
    @Value("${server.port:8080}")
    private int serverPort;

    /**
     * Generates a monthly resort report and uploads it to Amazon S3.
     *
     * <p>Fix cr-java-0062: Replaces the previous local-filesystem write operations:
     * <ul>
     *   <li>{@code new File(REPORT_BASE_PATH)} — directory creation on ephemeral local FS</li>
     *   <li>{@code reportDir.mkdirs()} — local directory creation</li>
     *   <li>{@code new FileWriter(fullPath)} — local file write (original line 42)</li>
     *   <li>{@code writer.write(...)} / {@code writer.close()} — local file I/O</li>
     * </ul>
     * All replaced with a single {@link S3Client#putObject} call that stores the CSV
     * directly in Amazon S3, ensuring data durability across container lifecycle events.</p>
     *
     * @param month the month for the report (e.g. "03")
     * @param year  the year for the report (e.g. "2024")
     * @return a result map containing status, S3 key, and bucket information
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // cr-java-0062 fix: replaced REPORT_BASE_PATH + fileName local path with an S3 object key.
        String s3Key = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        // cr-java-0062 fix: Build CSV content entirely in memory — no local filesystem dependency.
        String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

        // cr-java-0062 fix: Upload the report directly to Amazon S3 using AWS SDK for Java v2.
        // Replaces: new File(REPORT_BASE_PATH) / reportDir.mkdirs() / new FileWriter(fullPath)
        //           / writer.write(...) / writer.close()  [original lines 34-46]
        S3Client s3Client = S3Client.builder()
                .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1")))
                .build();

        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("bucket", s3BucketName);
            result.put("key", s3Key);
            result.put("serverPort", serverPort); // cr-java-0077 fix: reads from env var via @Value

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.awsErrorDetails().errorMessage());
        } finally {
            s3Client.close();
        }

        return result;
    }

    /**
     * Builds a download URL for the given report name.
     *
     * <p>Fix cr-java-0071: The previously hard-coded URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} has been replaced with
     * a value retrieved from AWS Systems Manager Parameter Store. The SSM parameter name
     * is read from the environment variable {@code SSM_PARAM_REPORT_BASE_URL}
     * (default: {@code /resortslite/reports/base-url}), enabling environment-specific
     * base URLs to be managed centrally without code changes.</p>
     *
     * @param reportName the name of the report file
     * @return the download URL string
     */
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // cr-java-0071 FIX: Hard-coded environment URL replaced with value retrieved from
        // AWS Systems Manager Parameter Store. The parameter name is read from the
        // environment variable SSM_PARAM_REPORT_BASE_URL (default:
        // /resortslite/reports/base-url) so the base URL can be changed per environment
        // without any code modification.
        String ssmParamName = System.getenv().getOrDefault(
                "SSM_PARAM_REPORT_BASE_URL", "/resortslite/reports/base-url");
        String awsRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");

        String reportBaseUrl;
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {
            GetParameterResponse paramResponse = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(ssmParamName)
                            .withDecryption(false)
                            .build());
            reportBaseUrl = paramResponse.parameter().value();
        }

        return reportBaseUrl + reportName;
    }

    /**
     * Returns system information including S3 storage references and server configuration.
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // cr-java-0111 fix: Replaced java.util.Date / SimpleDateFormat (server-local timezone)
        // with java.time.ZonedDateTime at UTC (ZoneOffset.UTC) formatted via DateTimeFormatter.
        // Standardising on UTC eliminates timezone inconsistencies across cloud regions/containers.
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        // cr-java-0062 fix: replaced hard-coded REPORT_BASE_PATH and BACKUP_PATH with S3 references.
        info.put("reportBucket", s3BucketName);
        info.put("reportPrefix", "reports/");
        info.put("backupPrefix", s3BackupPrefix);
        info.put("serverPort", serverPort);         // cr-java-0077 fix: reads from env var via @Value
        info.put("generatedAt", timestamp);
        return info;
    }
}
