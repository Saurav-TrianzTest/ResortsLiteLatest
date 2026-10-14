package com.demo.resortslite;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // GCS bucket name injected from environment variable / application properties
    @Value("${gcs.report.bucket:resort-reports-bucket}")
    private String reportBucket;

    // GCS object prefix (replaces the former hard-coded REPORT_BASE_PATH)
    @Value("${gcs.report.prefix:reports/}")
    private String reportPrefix;

    // GCS object prefix for backups (replaces the former hard-coded BACKUP_PATH)
    @Value("${gcs.backup.prefix:backups/nightly/}")
    private String backupPrefix;

    // cr-java-0077 FIX: Hard-coded port 8080 replaced with environment-variable-driven configuration.
    // GCP Cloud Run automatically injects the PORT env var; GKE deployments can set SERVER_PORT.
    // The @Value expression reads server.port from application.properties, which itself resolves
    // ${PORT:${SERVER_PORT:8080}} — giving Cloud Run / GKE full control over dynamic port binding.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0071: Externalized report download base URL via environment variable.
    // Set REPORT_DOWNLOAD_BASE_URL in GCP Cloud Run / GKE environment, or store the
    // value in GCP Secret Manager and reference it via sm:// in application.properties.
    @Value("${app.report.download.base-url:${REPORT_DOWNLOAD_BASE_URL:https://reports.resorts-internal.com/download}}")
    private String reportDownloadBaseUrl;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // Build the GCS object name using the configurable prefix
        String objectName = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // Upload the report to Google Cloud Storage
            Storage storage = StorageOptions.getDefaultInstance().getService();
            BlobId blobId = BlobId.of(reportBucket, objectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType("text/csv")
                    .build();
            storage.create(blobInfo, csvContent.toString().getBytes(StandardCharsets.UTF_8));

            String gcsUri = "gs://" + reportBucket + "/" + objectName;
            result.put("status", "generated");
            result.put("path", gcsUri);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // cr-java-0071 FIX: Hard-coded environment URL replaced with externalized configuration.
        // The report download base URL is now injected via @Value from application.properties
        // (app.report.download.base-url) or the REPORT_DOWNLOAD_BASE_URL environment variable,
        // enabling seamless deployment across dev / staging / production GCP environments without
        // code changes. Sensitive endpoint values can be stored in GCP Secret Manager and
        // referenced using the sm:// URI scheme in application.properties.
        return reportDownloadBaseUrl + "/" + reportName;
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // cr-java-0111 FIX: Replaced server-local timezone-dependent SimpleDateFormat/Date with
        // java.time.Instant formatted in UTC via DateTimeFormatter. This ensures consistent,
        // timezone-independent timestamps across all GCP Cloud Run instances and regions.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // Report and backup paths now reference GCS URIs instead of local file system paths
        info.put("reportPath", "gs://" + reportBucket + "/" + reportPrefix);
        info.put("backupPath", "gs://" + reportBucket + "/" + backupPrefix);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
