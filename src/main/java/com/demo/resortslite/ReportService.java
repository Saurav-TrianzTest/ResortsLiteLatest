package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Service responsible for generating and storing resort reports.
 *
 * <p>cr-java-0063 FIX: All java.io.File-based persistent storage operations have been
 * replaced with Amazon S3 client calls (AWS SDK for Java v2) to achieve cloud-native,
 * durable, and scalable storage without host-level file-system dependencies.
 *
 * <p>Specifically replaced (source lines 37, 39, 42):
 * <ul>
 *   <li>Line 37: {@code new File(REPORT_BASE_PATH)} → S3 bucket/key configuration via @Value</li>
 *   <li>Line 39: {@code reportDir.mkdirs()} → removed (S3 does not require directory creation)</li>
 *   <li>Line 42: {@code new FileWriter(fullPath)} → {@code S3Client.putObject()} with RequestBody</li>
 * </ul>
 *
 * <p>cr-java-0071 FIX: The hardcoded report download URL
 * {@code "http://reports.resorts-internal.com:8080/download/"} (source line 66) has been
 * replaced with a configurable base URL injected via {@code @Value} from the
 * {@code app.report.download.base-url} property, which is externalised to AWS Systems Manager
 * Parameter Store. Each deployment environment supplies its own SSM parameter value, enabling
 * environment-agnostic deployments without code changes.
 */
@Service
public class ReportService {

    /**
     * S3 bucket name for report storage.
     * cr-java-0063 FIX (source line 37): Replaces {@code new File(REPORT_BASE_PATH)}
     * where REPORT_BASE_PATH was the hard-coded local path "/var/legacy/reports/".
     * Resolved from environment variable AWS_S3_REPORTS_BUCKET or application property
     * app.s3.reports.bucket; defaults to "resort-reports-bucket".
     */
    @Value("${app.s3.reports.bucket:${AWS_S3_REPORTS_BUCKET:resort-reports-bucket}}")
    private String reportsBucket;

    /**
     * S3 key prefix for nightly backup objects.
     * Replaces the former hard-coded Windows path "C:\\ResortBackups\\nightly\\".
     * Resolved from environment variable AWS_S3_BACKUP_PREFIX or application property
     * app.s3.backup.prefix; defaults to "backups/nightly/".
     */
    @Value("${app.s3.backup.prefix:${AWS_S3_BACKUP_PREFIX:backups/nightly/}}")
    private String backupPrefix;

    /**
     * S3 key prefix for report objects.
     * cr-java-0063 FIX (source line 42): Replaces the local path prefix used when
     * constructing the {@code fullPath} argument passed to {@code new FileWriter(fullPath)}.
     * Resolved from environment variable AWS_S3_REPORTS_PREFIX or application property
     * app.s3.reports.prefix; defaults to "reports/".
     */
    @Value("${app.s3.reports.prefix:${AWS_S3_REPORTS_PREFIX:reports/}}")
    private String reportsPrefix;

    /** AWS region used when constructing the S3 client and building S3 URLs. */
    @Value("${app.s3.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * cr-java-0071 FIX: Base URL for report downloads, externalised to AWS Systems Manager
     * Parameter Store via the {@code app.report.download.base-url} property.
     *
     * <p>Source line 66 (original):
     * {@code return "http://reports.resorts-internal.com:8080/download/" + reportName;}
     *
     * <p>The hardcoded plain-HTTP URL pointing to an environment-specific internal host has
     * been removed. The base URL is now resolved at startup from the application property
     * {@code app.report.download.base-url}, which should be backed by an SSM Parameter Store
     * entry such as:
     * <pre>
     *   /resortslite/{env}/report/download-base-url  →  https://reports.resorts-internal.com/download/
     * </pre>
     * This allows dev, staging, and production environments to each supply their own endpoint
     * without any code change, satisfying cloud-native externalized configuration principles.
     */
    @Value("${app.report.download.base-url:${APP_REPORT_DOWNLOAD_BASE_URL:https://reports.resorts-internal.com/download/}}")
    private String reportDownloadBaseUrl;

    /**
     * cr-java-0077 FIX: Server port externalised to AWS Systems Manager Parameter Store and
     * injected at runtime via the {@code SERVER_PORT} environment variable (or the
     * {@code server.port} Spring property).
     *
     * <p>Source line 28 (original):
     * {@code private static final int SERVER_PORT = 8080; // czr-port-001}
     *
     * <p>The hard-coded value {@code 8080} has been removed. The port is now resolved from
     * the {@code SERVER_PORT} environment variable (injected by ECS task definition, EKS
     * pod spec, or Elastic Beanstalk environment properties) with a safe default of {@code 8080}
     * so that local development continues to work without additional configuration.
     */
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    /**
     * Generates a monthly resort report and uploads it to Amazon S3.
     *
     * <p>cr-java-0063 FIX summary for this method:
     * <ul>
     *   <li>Source line 37 — {@code new File(REPORT_BASE_PATH)}: removed; S3 does not
     *       require a {@link java.io.File} handle to represent a storage location.</li>
     *   <li>Source line 39 — {@code reportDir.mkdirs()}: removed; S3 object namespaces
     *       are virtual and require no directory pre-creation.</li>
     *   <li>Source line 42 — {@code new FileWriter(fullPath)}: replaced by
     *       {@link S3Client#putObject(PutObjectRequest, RequestBody)} which streams the
     *       CSV content directly to the configured S3 bucket.</li>
     * </ul>
     *
     * @param month the month for which the report is generated
     * @param year  the year for which the report is generated
     * @return a map containing the operation status and the S3 object key / URL
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // cr-java-0063 FIX (source line 42): S3 object key replaces the local fullPath
        // that was built as REPORT_BASE_PATH + fileName and passed to new FileWriter(fullPath).
        String s3Key = reportsPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            S3Client s3 = buildS3Client();

            // Assemble CSV content in memory — no local File or FileWriter needed.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // cr-java-0063 FIX (source lines 37, 39, 42):
            //   BEFORE: new File(REPORT_BASE_PATH)  →  reportDir.mkdirs()  →  new FileWriter(fullPath)
            //   AFTER : PutObjectRequest + RequestBody.fromString() uploaded directly to S3.
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3.putObject(putRequest, RequestBody.fromString(csvContent));

            String s3Url = "https://" + reportsBucket + ".s3." + awsRegion + ".amazonaws.com/" + s3Key;

            result.put("status", "generated");
            result.put("s3Key", s3Key);
            result.put("s3Url", s3Url);
            result.put("serverPort", serverPort); // cr-java-0077 FIX: injected via @Value / AWS SSM

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.awsErrorDetails().errorMessage());
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for a report using the externalized base URL.
     *
     * <p>cr-java-0071 FIX: The hardcoded plain-HTTP URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} (source line 66) has been
     * replaced with the {@code reportDownloadBaseUrl} field, which is injected via {@code @Value}
     * from the {@code app.report.download.base-url} property backed by AWS Systems Manager
     * Parameter Store. This enables environment-agnostic deployments.
     *
     * @param reportName the report file name to append to the base URL
     * @return a fully qualified download URL for the specified report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX (source line 66):
        //   BEFORE: return "http://reports.resorts-internal.com:8080/download/" + reportName;
        //   AFTER : base URL is externalised via @Value / AWS SSM Parameter Store.
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information including S3 storage configuration.
     *
     * @return a map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX (source line 70):
        //   BEFORE: new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
        //   AFTER : java.time.Instant / DateTimeFormatter standardised on UTC to avoid
        //           timezone inconsistencies across distributed cloud containers/regions.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 FIX: Expose S3 bucket/prefix config instead of hard-coded local paths
        info.put("reportsBucket", reportsBucket);           // replaces REPORT_BASE_PATH ("/var/legacy/reports/")
        info.put("backupPrefix", backupPrefix);             // replaces BACKUP_PATH ("C:\\ResortBackups\\nightly\\")
        info.put("reportsPrefix", reportsPrefix);           // replaces REPORT_BASE_PATH second usage
        info.put("reportDownloadBaseUrl", reportDownloadBaseUrl); // cr-java-0071 FIX
        info.put("serverPort", serverPort);                 // cr-java-0077 FIX: injected via @Value / AWS SSM
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Builds and returns an {@link S3Client} using the default AWS credentials provider chain.
     *
     * <p>The default credentials provider chain resolves credentials from environment variables
     * (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY), EC2 instance profiles, ECS task roles,
     * and other standard AWS credential sources — making this suitable for all AWS deployment targets.
     *
     * @return a configured {@link S3Client} instance
     */
    private S3Client buildS3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
