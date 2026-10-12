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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native report generation service.
 *
 * <p><strong>cr-java-0063 fix (Java.io.File Usage for Data Storage):</strong><br>
 * All {@code java.io.File} persistent storage operations (original lines 37, 39, 42) have been
 * replaced with Amazon S3 client calls using the AWS SDK for Java v2. The ephemeral container
 * file system is no longer used for data persistence; reports are stored durably in S3,
 * achieving cloud-native, durable, and scalable storage without host-level file system
 * dependencies.</p>
 *
 * <p><strong>cr-java-0071 fix (Hard-coded Environment URLs):</strong><br>
 * The hard-coded report download URL
 * {@code "http://reports.resorts-internal.com:8080/download/"} (original line 66)
 * has been replaced with a value retrieved at runtime from
 * <strong>AWS Systems Manager Parameter Store</strong>.  The parameter name is
 * configurable via the {@code REPORT_URL_PARAM_NAME} environment variable
 * (default: {@code /resortslite/reports/download-base-url}).  This enables
 * environment-agnostic deployments: the same artifact can be promoted from
 * dev → staging → production simply by updating the SSM parameter value —
 * no code or image rebuild required.</p>
 *
 * <p>Original violations removed:</p>
 * <ul>
 *   <li>Line 37: {@code String fullPath = REPORT_BASE_PATH + fileName;} — local FS path
 *       replaced by S3 object key.</li>
 *   <li>Line 39: {@code File reportDir = new File(REPORT_BASE_PATH);} — {@code java.io.File}
 *       directory creation replaced by S3 bucket reference (no directory creation needed).</li>
 *   <li>Line 42: {@code FileWriter writer = new FileWriter(fullPath);} — local file write
 *       replaced by {@code S3Client.putObject()} call.</li>
 *   <li>Line 66: {@code return "http://reports.resorts-internal.com:8080/download/" + reportName;}
 *       — hard-coded environment URL replaced by AWS SSM Parameter Store lookup.</li>
 * </ul>
 */
@Service
public class ReportService {

    /**
     * S3 bucket name for report storage.
     * Injected from the environment variable {@code REPORT_S3_BUCKET} (via
     * {@code cloud.aws.s3.report-bucket} in application.properties).
     * Replaces the hard-coded local path {@code /var/legacy/reports/} (cr-java-0061 / cr-java-0063).
     */
    @Value("${cloud.aws.s3.report-bucket:${REPORT_S3_BUCKET:resorts-lite-reports}}")
    private String reportBucket;

    /**
     * S3 key prefix for nightly backup objects.
     * Injected from the environment variable {@code BACKUP_S3_PREFIX} (via
     * {@code cloud.aws.s3.backup-prefix} in application.properties).
     * Replaces the hard-coded Windows path {@code C:\\ResortBackups\\nightly\\} (cr-java-0061 / cr-java-0063).
     */
    @Value("${cloud.aws.s3.backup-prefix:${BACKUP_S3_PREFIX:backups/nightly/}}")
    private String backupPrefix;

    /**
     * AWS region used when constructing the S3 and SSM clients.
     * Injected from the environment variable {@code AWS_REGION} (via
     * {@code cloud.aws.region.static} in application.properties).
     */
    @Value("${cloud.aws.region.static:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * SSM Parameter Store parameter name that holds the report download base URL.
     * Injected from the environment variable {@code REPORT_URL_PARAM_NAME}
     * (via {@code app.ssm.report-url-param} in application.properties).
     *
     * <p>cr-java-0071 fix: replaces the hard-coded URL string with an externalized
     * SSM parameter reference so the URL can differ per environment without code changes.</p>
     */
    @Value("${app.ssm.report-url-param:${REPORT_URL_PARAM_NAME:/resortslite/reports/download-base-url}}")
    private String reportUrlParamName;

    /**
     * Builds an {@link S3Client} using the configured AWS region.
     * Uses the default credential provider chain so that IAM roles, environment
     * variables, or instance profiles are all supported without hard-coded credentials.
     *
     * @return a configured {@link S3Client} instance
     */
    private S3Client buildS3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Builds an {@link SsmClient} using the configured AWS region.
     * Uses the default credential provider chain so that IAM roles, environment
     * variables, or instance profiles are all supported without hard-coded credentials.
     *
     * @return a configured {@link SsmClient} instance
     */
    private SsmClient buildSsmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Generates a monthly report CSV and uploads it to Amazon S3.
     *
     * <p><strong>cr-java-0063 fix — lines 37, 39, 42 of original source:</strong><br>
     * The original implementation used {@code java.io.File} for persistent data storage:</p>
     * <pre>
     *   // Line 37 — local FS path construction (java.io.File dependency):
     *   String fullPath = REPORT_BASE_PATH + fileName;
     *
     *   // Line 39 — java.io.File directory creation (cr-java-0063 violation):
     *   File reportDir = new File(REPORT_BASE_PATH);
     *   if (!reportDir.exists()) { reportDir.mkdirs(); }
     *
     *   // Line 42 — java.io.File write operation (cr-java-0063 violation):
     *   FileWriter writer = new FileWriter(fullPath);
     *   writer.write("BookingID,...\n");
     *   writer.close();
     * </pre>
     * <p>In cloud/containerised environments the local file system is ephemeral; data written
     * locally is lost on container restart or scale-out events. The fix replaces every
     * {@code java.io.File} / {@code FileWriter} operation with an Amazon S3 {@code PutObject}
     * call, ensuring durable, highly-available storage that survives container lifecycle events.</p>
     *
     * @param month two-digit month string (e.g. "03")
     * @param year  four-digit year string  (e.g. "2024")
     * @return result map containing upload status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // cr-java-0063 fix (line 37): S3 object key replaces the former local file path.
        // Original: String fullPath = REPORT_BASE_PATH + fileName;
        String objectKey = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0063 fix (line 39): Removed java.io.File directory creation.
            // Original: File reportDir = new File(REPORT_BASE_PATH);
            //           if (!reportDir.exists()) { reportDir.mkdirs(); }
            // S3 does not require directory creation; the bucket must exist and be accessible.

            // Build CSV content in memory (same data as the original FileWriter calls).
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // cr-java-0063 fix (line 42): Upload CSV content directly to S3 using AWS SDK v2.
            // Original: FileWriter writer = new FileWriter(fullPath);
            //           writer.write("BookingID,...\n");
            //           writer.write("BK-001,...\n");
            //           writer.write("BK-002,...\n");
            //           writer.close();
            S3Client s3 = buildS3Client();
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();
            s3.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", reportBucket);
            result.put("s3Key", objectKey);

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.awsErrorDetails().errorMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL by retrieving the base URL from AWS Systems Manager
     * Parameter Store and appending the report name.
     *
     * <p><strong>cr-java-0071 fix — original line 66:</strong><br>
     * Original hard-coded value:
     * {@code return "http://reports.resorts-internal.com:8080/download/" + reportName;}<br>
     * Replaced with a runtime lookup against SSM Parameter Store using the parameter name
     * stored in {@link #reportUrlParamName}.  The parameter value is managed per environment
     * in SSM, enabling zero-code-change promotions across dev/staging/prod.  The SSM
     * parameter should contain the full base URL including scheme and host
     * (e.g. {@code https://reports.resorts-prod.example.com/download/}).</p>
     *
     * @param reportName the report file name / path suffix to append to the base URL
     * @return the full report download URL constructed from the SSM-stored base URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 fix (line 66): retrieve the environment-specific report download
        // base URL from AWS Systems Manager Parameter Store instead of using a hard-coded string.
        // Original: return "http://reports.resorts-internal.com:8080/download/" + reportName;
        try (SsmClient ssmClient = buildSsmClient()) {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(reportUrlParamName)
                    .withDecryption(false)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            String baseUrl = response.parameter().value();
            // Ensure the base URL ends with a slash before appending the report name.
            if (!baseUrl.endsWith("/")) {
                baseUrl = baseUrl + "/";
            }
            return baseUrl + reportName;
        }
    }

    /**
     * Returns system information using cloud-native configuration values.
     *
     * <p>Replaced hard-coded {@code REPORT_BASE_PATH} and {@code BACKUP_PATH} references
     * with S3 bucket/prefix values (cr-java-0061 / cr-java-0063 fix).</p>
     *
     * @return map of current system configuration values
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 fix: replaced java.util.Date/SimpleDateFormat with java.time.Instant (UTC) for cloud-safe, timezone-independent timestamps
        String timestamp = Instant.now().toString();
        Map<String, Object> info = new HashMap<>();
        // Replaced: info.put("reportPath", REPORT_BASE_PATH)  → S3 bucket reference (cr-java-0063)
        info.put("reportBucket", reportBucket);
        // Replaced: info.put("backupPath", BACKUP_PATH)       → S3 backup prefix reference (cr-java-0063)
        info.put("backupPrefix", backupPrefix);
        info.put("generatedAt", timestamp);
        return info;
    }
}
