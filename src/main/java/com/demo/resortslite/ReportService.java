package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// FIXED (issue-7 / JAVA8_TO_21_DATE_TIME_CHANGES): Replaced legacy java.util.Date and
// SimpleDateFormat with java.time.LocalDateTime and DateTimeFormatter.
// SimpleDateFormat is not thread-safe and is a legacy API deprecated in favour of
// the java.time package introduced in Java 8 (JSR-310).
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // NOTE: Hardcoded absolute paths break containerisation.
    // Use environment variables or cloud object storage (S3/Azure Blob) instead.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/";
    private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\";
    private static final int SERVER_PORT = 8080;

    // FIXED (issue-7): Using thread-safe DateTimeFormatter instead of SimpleDateFormat.
    // DateTimeFormatter instances are immutable and thread-safe; SimpleDateFormat is not.
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Generates a monthly booking report CSV file.
     *
     * @param month the month identifier (e.g., "2024-03")
     * @param year  the year identifier (e.g., "2024")
     * @return a map containing the generation status and file path
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

            // FIXED (issue-10 / JAVA8_TO_21_RESOURCE_MANAGEMENT): FileWriter is now wrapped
            // in a try-with-resources block, ensuring it is always closed even if an
            // IOException is thrown. This prevents resource leaks.
            // FIXED (issue-8 / JAVA8_TO_21_UTF8_DEFAULT_CHARSET): Explicitly specifying
            // UTF-8 charset — Java 18+ (JEP 400) changed the default charset to UTF-8
            // regardless of OS locale, but explicit charset ensures portability.
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
     * NOTE: URL uses plain HTTP — use HTTPS for cloud/production deployments.
     *
     * @param reportName the name of the report file
     * @return the download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        return "http://reports.resorts-internal.com:8080/download/" + reportName;
    }

    /**
     * Returns system information including paths and current timestamp.
     * FIXED (issue-7): Uses LocalDateTime.now() + DateTimeFormatter instead of
     * new Date() + SimpleDateFormat for thread-safe, modern date/time handling.
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // FIXED (issue-7): Replaced new Date() + SimpleDateFormat.format() with
        // LocalDateTime.now().format(DateTimeFormatter) — thread-safe and non-deprecated.
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);
        info.put("backupPath", BACKUP_PATH);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
