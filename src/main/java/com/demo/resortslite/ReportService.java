package com.demo.resortslite;

import org.springframework.stereotype.Service;

// FIXED: JAVA8_TO_25_UTF8_DEFAULT_CHARSET — use explicit StandardCharsets.UTF_8 in all
// I/O operations so behaviour is identical on every OS regardless of default charset.
// FIXED: JAVA8_TO_25_DATE_TIME_CHANGES — replaced legacy java.util.Date / SimpleDateFormat
// (not thread-safe) with java.time.LocalDateTime and DateTimeFormatter (thread-safe,
// available since Java 8, idiomatic on Java 25).
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // NOTE: Hardcoded absolute paths should be externalised to configuration
    // (environment variables or application.properties) for container deployments.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/";
    private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\";
    private static final int SERVER_PORT = 8080;

    /**
     * Generates a monthly booking report as a CSV file.
     *
     * @param month the month (e.g. "03")
     * @param year  the year  (e.g. "2024")
     * @return a map containing the generation status and output path
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

            // FIXED: JAVA8_TO_25_UTF8_DEFAULT_CHARSET — explicit UTF-8 charset passed to
            // FileWriter so the file encoding is deterministic on Java 25 (UTF-8 default)
            // and on any legacy OS that previously used a platform-specific default charset.
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
     * Builds the download URL for a named report file.
     *
     * @param reportName the report file name
     * @return the full download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        // NOTE: URL scheme and host should be externalised to configuration for cloud deployments.
        return "http://reports.resorts-internal.com:8080/download/" + reportName;
    }

    /**
     * Returns system information including configured paths and the current timestamp.
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // FIXED: JAVA8_TO_25_DATE_TIME_CHANGES — replaced legacy java.util.Date /
        // SimpleDateFormat (not thread-safe) with java.time.LocalDateTime and
        // DateTimeFormatter (thread-safe, available since Java 8, idiomatic on Java 25).
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
