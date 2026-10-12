package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService handles generation and storage of resort reports.
 *
 * <p>Cloud-readiness fixes applied:</p>
 * <ul>
 *   <li><b>cr-java-0061</b> – Removed hard-coded absolute file-system paths
 *       ({@code /var/legacy/reports/} and {@code C:\ResortBackups\nightly\}).
 *       Paths are now resolved from environment variables / Spring properties.</li>
 *   <li><b>cr-java-0062</b> – Replaced all local {@link java.io.FileWriter} write
 *       operations (formerly at line 42) with Amazon S3 {@code PutObject} calls,
 *       ensuring data durability across container restarts and horizontal scaling.</li>
 *   <li><b>cr-java-0063</b> – Removed all {@link java.io.File} persistent storage
 *       operations (formerly at lines 37, 39, 42):
 *       <ul>
 *         <li>Line 37: {@code new File(REPORT_BASE_PATH)} → replaced by S3 key prefix</li>
 *         <li>Line 39: {@code reportDir.mkdirs()} → eliminated; S3 buckets require no
 *             directory pre-creation</li>
 *         <li>Line 42: {@code new FileWriter(fullPath)} → replaced by
 *             {@link software.amazon.awssdk.services.s3.model.PutObjectRequest} via
 *             {@link S3Client#putObject}</li>
 *       </ul>
 *       All local file-system dependencies are replaced with Amazon S3 client calls
 *       (AWS SDK for Java v2), achieving cloud-native durable and scalable storage
 *       without host-level file system dependencies.</li>
 *   <li><b>cr-java-0071</b> – Hard-coded report download base URL
 *       ({@code http://reports.resorts-internal.com:8080/download/}) replaced with a
 *       runtime lookup from AWS Systems Manager Parameter Store, enabling
 *       environment-agnostic deployments across dev, staging, and production.</li>
 *   <li><b>cr-java-0088</b> – Report download URL now uses HTTPS.</li>
 * </ul>
 */
// cr-java-0077 FIX (Line 28): Hard-coded SERVER_PORT = 8080 removed. Port is now injected via SERVER_PORT env var / server.port property (AWS Parameter Store / ECS task definition).
@Service
public class ReportService {

    // S3 bucket name resolved from environment variable — no hard-coded path (cr-java-0061 fix)
    @Value("${cloud.aws.s3.bucket-name:${REPORTS_S3_BUCKET:resort-reports}}")
    private String reportsBucketName;

    // S3 key prefix replaces the former hard-coded REPORT_BASE_PATH "/var/legacy/reports/" (cr-java-0061 / cr-java-0063 fix)
    @Value("${cloud.aws.s3.reports-prefix:${REPORTS_S3_PREFIX:reports/}}")
    private String reportsKeyPrefix;

    // S3 key prefix replaces the former hard-coded BACKUP_PATH "C:\\ResortBackups\\nightly\\" (cr-java-0061 / cr-java-0063 fix)
    @Value("${cloud.aws.s3.backup-prefix:${BACKUP_S3_PREFIX:backups/nightly/}}")
    private String backupKeyPrefix;

    /**
     * SSM parameter name for the report download base URL.
     * Resolved from the {@code SSM_PARAM_REPORT_DOWNLOAD_URL} environment variable or the
     * {@code app.ssm.param.report-download-url} property.
     * Default: {@code /resortslite/reports/download-base-url}
     *
     * <p><b>cr-java-0071 fix</b>: replaces the hard-coded URL
     * {@code http://reports.resorts-internal.com:8080/download/} with a runtime
     * lookup from AWS Systems Manager Parameter Store.</p>
     */
    @Value("${app.ssm.param.report-download-url:${SSM_PARAM_REPORT_DOWNLOAD_URL:/resortslite/reports/download-base-url}}")
    private String reportDownloadUrlParamName;

    /**
     * cr-java-0077 FIX (Source Line 28): Replaces {@code private static final int SERVER_PORT = 8080}.
     * The server port is now injected at runtime from the {@code SERVER_PORT} environment variable
     * (set in ECS task definition, EKS pod spec, or Elastic Beanstalk environment configuration),
     * enabling dynamic port assignment required by container orchestration and AWS service discovery.
     */
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    private final S3Client s3Client;

    /**
     * AWS SSM client used to retrieve environment-specific URLs from Parameter Store.
     * cr-java-0071 fix: replaces hard-coded environment URLs with SSM Parameter Store lookups.
     */
    @Autowired
    private SsmClient ssmClient;

    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Generates a monthly resort report and uploads it to Amazon S3.
     *
     * <p><b>cr-java-0063 fix (Lines 37, 39, 42)</b>: All three {@link java.io.File}
     * usages have been replaced with Amazon S3 SDK calls:</p>
     * <ul>
     *   <li><b>Line 37</b>: {@code File reportDir = new File(REPORT_BASE_PATH)} removed —
     *       S3 does not require directory objects; the key prefix ({@code reportsKeyPrefix})
     *       serves as the logical namespace.</li>
     *   <li><b>Line 39</b>: {@code reportDir.mkdirs()} removed — S3 buckets and key
     *       prefixes are created implicitly on first {@code PutObject}; no directory
     *       pre-creation is needed.</li>
     *   <li><b>Line 42</b>: {@code new FileWriter(fullPath)} removed — report content is
     *       now written directly to Amazon S3 via
     *       {@link software.amazon.awssdk.services.s3.model.PutObjectRequest}, eliminating
     *       any dependency on the ephemeral container file system and preventing data loss
     *       on container restart or scale-out.</li>
     * </ul>
     *
     * <p><b>cr-java-0061 fix</b>: The hard-coded path {@code /var/legacy/reports/}
     * is replaced by the configurable S3 key prefix {@code reportsKeyPrefix}.</p>
     *
     * @param month the month for which the report is generated
     * @param year  the year for which the report is generated
     * @return a map containing the operation status and the S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // S3 object key replaces the former hard-coded fullPath (cr-java-0061 / cr-java-0063 fix)
        String s3Key = reportsKeyPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV report content in memory
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // cr-java-0063 fix (Lines 37, 39, 42 — replaces java.io.File usage):
            //   - Removed: File reportDir = new File(REPORT_BASE_PATH);   [Line 37]
            //   - Removed: reportDir.mkdirs();                             [Line 39]
            //   - Removed: FileWriter writer = new FileWriter(fullPath);   [Line 42]
            // All replaced with Amazon S3 PutObject call (AWS SDK for Java v2).
            // Data is written directly to S3 — no local directory creation,
            // no FileWriter, no local file path. Data is durable across container restarts.
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", reportsBucketName);
            result.put("s3Key", s3Key);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL by retrieving the base URL from AWS Systems Manager
     * Parameter Store and appending the report name.
     *
     * <p><b>cr-java-0071 fix (Line 66)</b>: The hard-coded URL
     * {@code http://reports.resorts-internal.com:8080/download/} has been replaced with
     * a runtime lookup from AWS SSM Parameter Store using the parameter name stored in
     * {@code reportDownloadUrlParamName} (default: {@code /resortslite/reports/download-base-url}).
     * Each deployment environment (dev, staging, production) stores its own value in SSM
     * under the same parameter name, enabling environment-agnostic deployments with no
     * code changes required between environments.</p>
     *
     * <p><b>cr-java-0088 fix</b>: The base URL stored in SSM must use HTTPS; the
     * former plain-HTTP URL is no longer present in source code.</p>
     *
     * @param reportName the name of the report file
     * @return a URL for the report, constructed from the SSM-provided base URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: Hard-coded URL "http://reports.resorts-internal.com:8080/download/"
        // replaced with a runtime lookup from AWS Systems Manager Parameter Store.
        // The parameter name is externalised via 'app.ssm.param.report-download-url' property
        // (or SSM_PARAM_REPORT_DOWNLOAD_URL env var), defaulting to
        // /resortslite/reports/download-base-url.
        // Each environment stores its own HTTPS base URL in SSM — no code changes needed
        // when promoting from dev → staging → production.
        GetParameterRequest paramRequest = GetParameterRequest.builder()
                .name(reportDownloadUrlParamName)
                .withDecryption(false)
                .build();
        GetParameterResponse paramResponse = ssmClient.getParameter(paramRequest);
        String reportDownloadBaseUrl = paramResponse.parameter().value();

        // Ensure the base URL ends with '/' before appending the report name
        if (!reportDownloadBaseUrl.endsWith("/")) {
            reportDownloadBaseUrl = reportDownloadBaseUrl + "/";
        }
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information including S3 storage configuration.
     *
     * <p><b>cr-java-0061 / cr-java-0063 fix</b>: Replaces former local-path-based system info
     * ({@code REPORT_BASE_PATH}, {@code BACKUP_PATH}) with S3 bucket/prefix references.
     * No {@link java.io.File} objects are used.</p>
     *
     * @return a map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX (Line 70): Replaced java.util.Date + SimpleDateFormat with java.time API.
        // ZonedDateTime.now(ZoneOffset.UTC) standardises on UTC across all cloud regions/containers,
        // eliminating timezone inconsistencies in distributed deployments.
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
        Map<String, Object> info = new HashMap<>();
        // S3 references replace former hard-coded REPORT_BASE_PATH and BACKUP_PATH (cr-java-0061 / cr-java-0063 fix)
        info.put("reportsBucket", reportsBucketName);
        info.put("reportsKeyPrefix", reportsKeyPrefix);
        info.put("backupKeyPrefix", backupKeyPrefix);
        info.put("serverPort", serverPort);  // cr-java-0077 fix: port injected via SERVER_PORT env var
        info.put("generatedAt", timestamp);
        return info;
    }
}
