package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import com.demo.resortslite.config.AwsParameterStoreConfig;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // FIXED cr-java-0061: Replaced hard-coded file paths with S3 configuration
    @Value("${aws.s3.bucket.name}")
    private String s3BucketName;

    @Value("${aws.s3.region}")
    private String awsRegion;

    @Value("${aws.s3.reports.prefix}")
    private String reportsPrefix;

    @Value("${aws.s3.backups.prefix}")
    private String backupsPrefix;

    // VIOLATION [Software Portability / High]: Fixed server port hardcoded in application logic.
    // Container orchestration (ECS / EKS) dynamically assigns ports. Hardcoded ports prevent
    // dynamic port binding required for modern container deployment and service discovery.
    private static final int SERVER_PORT = 8080; // czr-port-001

    @Autowired
    private AwsParameterStoreConfig parameterStoreConfig;

    private S3Client s3Client;

    @PostConstruct
    public void initializeS3Client() {
        // Initialize S3 client with default credentials provider (supports IAM roles, environment variables, etc.)
        this.s3Client = S3Client.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @PreDestroy
    public void closeS3Client() {
        if (s3Client != null) {
            s3Client.close();
        }
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // FIXED cr-java-0062: Generate report directly to S3 without local file system writes
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String s3Key = reportsPrefix + fileName; // FIXED cr-java-0061, cr-java-0062: Using S3 key for direct cloud storage

        Map<String, Object> result = new HashMap<>();

        try {
            // FIXED cr-java-0062: All data generated in-memory and uploaded directly to S3
            // Generate CSV content in-memory (no local file system writes)
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // FIXED cr-java-0061, cr-java-0062: Upload directly to S3 for durable, scalable storage
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromString(csvContent.toString()));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            result.put("location", "s3://" + s3BucketName + "/" + s3Key);
            result.put("serverPort", SERVER_PORT); // czr-port-001

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", "S3 Error: " + e.awsErrorDetails().errorMessage());
            result.put("errorCode", e.awsErrorDetails().errorCode());
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
        // FIXED cr-java-0071 [Cloud Compatibility / Mandatory]: Environment-specific URL
        // externalized to AWS Systems Manager Parameter Store. Enables environment-agnostic
        // deployments without code changes. Default fallback provided for local development.
        String baseUrl = parameterStoreConfig.getParameter("report-download-base-url", "https://reports.resorts-internal.com:8080");
        return baseUrl + "/download/" + reportName; // cr-java-0071 FIXED
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // FIXED cr-java-0111: Migrated from java.util.Date to java.time API with UTC standardization
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // FIXED cr-java-0061: Return S3 configuration instead of file paths
        info.put("reportStorage", "s3://" + s3BucketName + "/" + reportsPrefix);
        info.put("backupStorage", "s3://" + s3BucketName + "/" + backupsPrefix);
        info.put("s3Bucket", s3BucketName);
        info.put("s3Region", awsRegion);
        info.put("serverPort", SERVER_PORT);        // czr-port-001
        info.put("generatedAt", timestamp);
        return info;
    }
}
