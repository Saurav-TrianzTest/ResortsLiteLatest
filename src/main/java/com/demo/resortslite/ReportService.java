package com.demo.resortslite;

import org.springframework.stereotype.Service;

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

    // NOTE: Hardcoded absolute path — use environment variables or cloud object storage
    // (e.g., S3 / Azure Blob) for portable, container-friendly deployments.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/";

    // NOTE: Windows-style absolute path will fail on Linux-based containers.
    // Externalise to environment variable or use cloud storage instead.
    private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\";

    // NOTE: Fixed server port hardcoded in application logic — container orchestration
    // (ECS / EKS) dynamically assigns ports. Externalise to configuration.
    private static final int SERVER_PORT = 8080;

    /**
     * Generates a monthly booking report as a CSV file.
     *
     * @param month the month for the report (e.g., "03")
     * @param year  the year for the report (e.g., "2024")
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

            // Updated: FileWriter now uses explicit UTF-8 charset (Java 25 compatibility —
            // avoids reliance on platform default encoding which may differ across JVMs).
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
     * NOTE: Plain HTTP URL — use HTTPS in production cloud environments.
     *
     * @param reportName the name of the report file
     * @return the download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        return "http://reports.resorts-internal.com:8080/download/" + reportName;
    }

    /**
     * Returns system information including configured paths, port, and current timestamp.
     * Updated: replaced deprecated {@link java.text.SimpleDateFormat} with
     * {@link java.time.LocalDateTime} and {@link java.time.format.DateTimeFormatter}
     * (java.time API is the recommended replacement since Java 8 and required for Java 25).
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // Updated: replaced SimpleDateFormat / new Date() with java.time API.
        // SimpleDateFormat is not thread-safe and is superseded by DateTimeFormatter.
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String timestamp = LocalDateTime.now().format(formatter);

        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);
        info.put("backupPath", BACKUP_PATH);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
