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

    // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute path.
    // /var/legacy/reports does not exist in a Docker container image. Must use volume mounts,
    // cloud object storage (S3 / Azure Blob), or an environment variable.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/"; // czr-java-001

    // VIOLATION czr-java-001 [Software Portability / Mandatory]: Windows-style absolute path
    // will fail on any Linux-based container or cloud host.
    private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\"; // czr-java-001

    // VIOLATION czr-port-001 [Software Portability / High]: Fixed server port hardcoded in
    // application logic. Container orchestration (ECS / EKS) dynamically assigns ports.
    private static final int SERVER_PORT = 8080; // czr-port-001

    /**
     * Generates a monthly booking report as a CSV file.
     *
     * @param month the month (e.g. "03")
     * @param year  the year  (e.g. "2024")
     * @return a map containing the generation status and file path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = REPORT_BASE_PATH + fileName; // czr-java-001

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(REPORT_BASE_PATH); // czr-java-001
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            // UPGRADED: FileWriter now uses explicit StandardCharsets.UTF_8 charset.
            // Java 8 used the platform default charset (often windows-1252 on Windows),
            // making file encoding non-deterministic across environments.
            // Java 18+ standardised the default to UTF-8, but naming it explicitly is
            // required for deterministic behaviour on all JDK versions
            // (rule JAVA8_TO_25_UTF8_DEFAULT_CHARSET).
            try (FileWriter writer = new FileWriter(fullPath, StandardCharsets.UTF_8)) {
                writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
                writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
                writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            }

            result.put("status", "generated");
            result.put("path", fullPath);
            result.put("serverPort", SERVER_PORT); // czr-port-001

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a named report.
     *
     * @param reportName the report file name
     * @return the download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
        // hardcoded for report download. Cloud security standards enforce HTTPS.
        return "http://reports.resorts-internal.com:8080/download/" + reportName; // cr-java-0088
    }

    /**
     * Returns system information including configured paths and the current timestamp.
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // UPGRADED: Replaced deprecated java.util.Date + SimpleDateFormat with java.time API.
        // java.util.Date and SimpleDateFormat are not thread-safe and are legacy APIs.
        // java.time.LocalDateTime + DateTimeFormatter is the standard replacement since Java 8
        // and remains the recommended approach on Java 25
        // (rule JAVA8_TO_25_DATE_TIME_CHANGES).
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);  // czr-java-001
        info.put("backupPath", BACKUP_PATH);        // czr-java-001
        info.put("serverPort", SERVER_PORT);        // czr-port-001
        info.put("generatedAt", timestamp);
        return info;
    }
}
