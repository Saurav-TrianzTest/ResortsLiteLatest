package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.FileWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — generates and serves resort booking reports.
 *
 * <p>Java 21 / Spring Boot 3.x compatibility notes:</p>
 * <ul>
 *   <li>{@link FileWriter} now constructed with an explicit {@link StandardCharsets#UTF_8}
 *       charset argument (available since Java 11) to avoid platform-default charset
 *       dependency. The default charset is UTF-8 since Java 18, but explicit is safer
 *       and eliminates compiler warnings on Java 17+.</li>
 *   <li>Legacy {@code java.util.Date} / {@code SimpleDateFormat} replaced with
 *       {@code java.time.LocalDateTime} / {@code DateTimeFormatter} (recommended
 *       date/time API since Java 8; {@code Date} and {@code Calendar} are legacy).</li>
 *   <li>{@link FileWriter} wrapped in try-with-resources to guarantee the stream is
 *       closed even if an exception is thrown (replaces manual close / finally block).</li>
 * </ul>
 */
@Service
public class ReportService {

    // NOTE czr-java-001: Hardcoded absolute path — /var/legacy/reports does not exist
    // inside a Docker container image. Breaks containerisation.
    // TODO: Replace with volume mount, cloud object storage (S3 / Azure Blob),
    //       or externalise to an environment variable.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/";

    // NOTE czr-java-001: Windows-style absolute path will fail on Linux-based containers.
    // TODO: Externalise to environment variable or use cloud storage.
    private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\";

    // NOTE czr-port-001: Fixed server port hardcoded in application logic.
    // Container orchestration (ECS / EKS) dynamically assigns ports.
    // TODO: Read from environment variable or Spring property.
    private static final int SERVER_PORT = 8080;

    /**
     * Generates a monthly CSV report and writes it to the configured report directory.
     *
     * <p>Uses try-with-resources to ensure {@link FileWriter} is always closed,
     * and passes {@link StandardCharsets#UTF_8} explicitly to avoid platform-default
     * charset issues (fixes the Java 17+ compiler warning on implicit charset use).</p>
     *
     * @param month month identifier (e.g., "03")
     * @param year  year identifier (e.g., "2024")
     * @return map containing generation status and file path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = REPORT_BASE_PATH + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(REPORT_BASE_PATH);
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            // try-with-resources ensures FileWriter is always closed (fixes resource leak).
            // StandardCharsets.UTF_8 passed explicitly — avoids platform-default charset
            // dependency (FileWriter(String, Charset) available since Java 11).
            try (FileWriter writer = new FileWriter(fullPath, StandardCharsets.UTF_8)) {
                writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
                writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
                writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            }

            result.put("status", "generated");
            result.put("path", fullPath);
            result.put("serverPort", SERVER_PORT);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a named report.
     *
     * <p>NOTE cr-java-0088: Plain HTTP URL hardcoded for report download.
     * TODO: Replace with HTTPS endpoint for cloud-native / AWS Well-Architected compliance.</p>
     *
     * @param reportName the report file name
     * @return download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        return "http://reports.resorts-internal.com:8080/download/" + reportName;
    }

    /**
     * Returns system information including configured paths and current timestamp.
     *
     * <p>Uses {@code java.time.LocalDateTime} / {@code DateTimeFormatter} instead of
     * the legacy {@code java.util.Date} / {@code SimpleDateFormat} (replaced as part of
     * Java 21 modernisation — {@code Date} and {@code Calendar} are legacy since Java 8).</p>
     *
     * @return map containing system info entries
     */
    public Map<String, Object> getSystemInfo() {
        // java.time API — replaces legacy java.util.Date / SimpleDateFormat
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);
        info.put("backupPath", BACKUP_PATH);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
