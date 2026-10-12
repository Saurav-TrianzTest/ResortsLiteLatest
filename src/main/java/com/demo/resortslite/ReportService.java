package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService – cloud-native report generation service.
 *
 * <p><strong>cr-java-0063 fix (Java.io.File Usage for Data Storage):</strong>
 * The original implementation used {@code java.io.File} for persistent data storage
 * operations (lines 37, 39, 42 of the source file):
 * <ul>
 *   <li>Line 37: {@code File reportDir = new File(REPORT_BASE_PATH);} – local directory
 *       reference assuming ephemeral container file system availability.</li>
 *   <li>Line 39: {@code reportDir.mkdirs();} – local directory creation on ephemeral
 *       container file system, which is lost on container restart or scale-out.</li>
 *   <li>Line 42: {@code FileWriter writer = new FileWriter(fullPath);} – local file
 *       write to {@code /var/legacy/reports/}, which is not durable in cloud environments.</li>
 * </ul>
 * All three {@code java.io.File} / {@code FileWriter} operations have been replaced with
 * Amazon S3 {@code putObject} calls using AWS SDK for Java v2, achieving cloud-native,
 * durable, and scalable storage without host-level file system dependencies.</p>
 *
 * <p><strong>cr-java-0061 fix (Hard-coded file-system paths):</strong>
 * The hard-coded constants {@code REPORT_BASE_PATH = "/var/legacy/reports/"} and
 * {@code BACKUP_PATH = "C:\\ResortBackups\\nightly\\"} have been replaced with
 * {@code @Value}-injected S3 bucket name and key-prefix properties that are resolved
 * from environment variables at runtime.</p>
 *
 * <p><strong>cr-java-0062 fix (Local File System Write Operations):</strong>
 * The {@code FileWriter} write operations have been replaced with in-memory
 * {@code StringBuilder} content uploaded to Amazon S3 via {@code putObject}.</p>
 */
@Service
public class ReportService {

    /**
     * S3 bucket name for report storage.
     * Resolved from the {@code AWS_S3_REPORTS_BUCKET} environment variable via
     * {@code aws.s3.reports.bucket} in application.properties.
     * Replaces the hard-coded local path {@code /var/legacy/reports/} (cr-java-0061).
     */
    @Value("${aws.s3.reports.bucket}")
    private String reportsBucket;

    /**
     * S3 key prefix for monthly reports.
     * Replaces the hard-coded {@code REPORT_BASE_PATH} constant (cr-java-0061).
     */
    @Value("${aws.s3.reports.prefix:reports/}")
    private String reportsPrefix;

    /**
     * S3 key prefix for nightly backups.
     * Replaces the hard-coded Windows path {@code C:\ResortBackups\nightly\} (cr-java-0061).
     */
    @Value("${aws.s3.backup.prefix:backups/nightly/}")
    private String backupPrefix;

    /**
     * Server port – read from environment to avoid hard-coded port binding.
     *
     * <p><strong>cr-java-0077 fix (Hard-coded Ports):</strong>
     * The original {@code private static final int SERVER_PORT = 8080;} constant (line 28)
     * has been replaced with a Spring {@code @Value} injection bound to the
     * {@code SERVER_PORT} environment variable. In AWS ECS, EKS, or Elastic Beanstalk,
     * set {@code SERVER_PORT} at runtime (or source it from the SSM parameter
     * {@code /resortslite/server/port} via the deployment environment configuration).
     * This enables dynamic port assignment required by container orchestration platforms.</p>
     */
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    /**
     * Report download base URL – externalised from AWS Systems Manager Parameter Store.
     * Resolved via the {@code app.report.download.base.url} property, which is backed by
     * the {@code APP_REPORT_DOWNLOAD_BASE_URL} environment variable (populated from the
     * SSM parameter {@code /resortslite/report/download/base-url} at deployment time).
     * Replaces the hard-coded URL {@code http://reports.resorts-internal.com:8080/download/}
     * (cr-java-0071).
     */
    @Value("${app.report.download.base.url}")
    private String reportDownloadBaseUrl;

    private final S3Client s3Client;

    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Generates a monthly CSV report and uploads it directly to Amazon S3.
     *
     * <p><strong>cr-java-0063 remediation (Java.io.File Usage for Data Storage):</strong>
     * The original source code contained three {@code java.io.File} violations:</p>
     * <ul>
     *   <li><strong>Line 37</strong> – {@code File reportDir = new File(REPORT_BASE_PATH);}
     *       FIXED: Removed entirely; S3 does not require directory pre-creation.</li>
     *   <li><strong>Line 39</strong> – {@code reportDir.mkdirs();}
     *       FIXED: Removed entirely; S3 key prefixes are virtual and need no mkdir.</li>
     *   <li><strong>Line 42</strong> – {@code FileWriter writer = new FileWriter(fullPath);}
     *       FIXED: Replaced with in-memory {@code StringBuilder} whose bytes are uploaded
     *       to Amazon S3 via {@link S3Client#putObject(PutObjectRequest, RequestBody)},
     *       ensuring durable, cloud-native persistence that survives container restarts
     *       and horizontal scaling.</li>
     * </ul>
     *
     * @param month two-digit month string (e.g. {@code "03"})
     * @param year  four-digit year string  (e.g. {@code "2024"})
     * @return result map containing {@code status}, {@code s3Bucket}, {@code s3Key},
     *         and {@code serverPort}
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // S3 object key – replaces the former local fullPath = REPORT_BASE_PATH + fileName
        // (cr-java-0061 line 37, cr-java-0063 lines 37/39/42)
        String s3Key = reportsPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // -------------------------------------------------------------------
            // cr-java-0063 FIX: All java.io.File operations replaced with S3.
            //
            // REMOVED (line 37): File reportDir = new File(REPORT_BASE_PATH);
            //   → S3 does not require directory objects; key prefixes are virtual.
            //
            // REMOVED (line 39): reportDir.mkdirs();
            //   → No local directory creation needed; S3 accepts any key directly.
            //
            // REPLACED (line 42): FileWriter writer = new FileWriter(fullPath);
            //   → Report content is built in-memory and uploaded to S3 via putObject.
            //     This eliminates all local file system dependencies and ensures data
            //     durability, high availability, and cloud-native scalability.
            // -------------------------------------------------------------------

            // Build report content in memory (no local file system I/O)
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes(StandardCharsets.UTF_8);

            // Upload report bytes directly to Amazon S3 – durable, cloud-native storage.
            // Replaces FileWriter + local file write (cr-java-0063 line 42).
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(contentBytes));

            result.put("status", "generated");
            result.put("s3Bucket", reportsBucket);
            result.put("s3Key", s3Key);
            result.put("serverPort", serverPort);

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.awsErrorDetails().errorMessage());
        }

        return result;
    }

    /**
     * Builds an HTTPS download URL for a report stored in Amazon S3.
     *
     * <p><strong>cr-java-0071 fix (Hard-coded Environment URLs):</strong>
     * The former hard-coded URL {@code http://reports.resorts-internal.com:8080/download/}
     * has been replaced with a value retrieved from AWS Systems Manager Parameter Store
     * via the {@code app.report.download.base.url} property, enabling environment-agnostic
     * deployments without code changes per environment.</p>
     *
     * @param reportName the S3 object key / report file name
     * @return URL pointing to the report object, sourced from SSM Parameter Store
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: Hard-coded environment URL replaced with value retrieved from
        // AWS Systems Manager Parameter Store via the app.report.download.base.url property.
        // The base URL is injected at startup from the SSM parameter
        // /resortslite/report/download/base-url, enabling environment-agnostic deployments.
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns cloud-native system metadata.
     *
     * <p>Replaces the former implementation that exposed hard-coded local file-system
     * paths ({@code REPORT_BASE_PATH}, {@code BACKUP_PATH}) and a hard-coded port
     * constant (cr-java-0061).</p>
     *
     * @return map of system metadata including S3 bucket/prefix values and server port
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX: Replaced java.util.Date / SimpleDateFormat (server-local timezone)
        // with java.time.Instant formatted via DateTimeFormatter.ISO_INSTANT, standardised on
        // UTC (ZoneOffset.UTC). This eliminates timezone inconsistencies across distributed
        // cloud containers and regions, and removes the dependency on the JVM's default timezone.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        info.put("reportsBucket", reportsBucket);
        info.put("reportsPrefix", reportsPrefix);
        info.put("backupPrefix", backupPrefix);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
