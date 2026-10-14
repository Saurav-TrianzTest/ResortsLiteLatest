package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // FIX cz-java-0057 (Line 19): Replaced hardcoded absolute path "/var/legacy/reports/"
    // with environment variable REPORT_BASE_PATH injected via Kubernetes ConfigMap /
    // Azure App Configuration. Falls back to "/app/reports/" if not set.
    private static final String REPORT_BASE_PATH = System.getenv("REPORT_BASE_PATH") != null
            ? System.getenv("REPORT_BASE_PATH")
            : "/app/reports/";

    // FIX cz-java-0057 (Line 23): Replaced hardcoded Windows-style absolute path
    // "C:\\ResortBackups\\nightly\\" with environment variable BACKUP_PATH injected
    // via Kubernetes ConfigMap / Azure App Configuration. Falls back to "/app/backups/".
    private static final String BACKUP_PATH = System.getenv("BACKUP_PATH") != null
            ? System.getenv("BACKUP_PATH")
            : "/app/backups/";

    // FIX cz-java-0061 [Hardcoded Ports]: Replaced hardcoded port value 8080 with an
    // environment variable SERVER_PORT injected via Kubernetes ConfigMap / AKS ConfigMap.
    // Container orchestration (EKS / AKS) dynamically assigns ports; hardcoded values
    // prevent flexible container deployment and service discovery.
    @Value("${SERVER_PORT:8080}")
    private int serverPort;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = REPORT_BASE_PATH + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(REPORT_BASE_PATH);
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            FileWriter writer = new FileWriter(fullPath);
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.close();

            result.put("status", "generated");
            result.put("path", fullPath);
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
        // hardcoded for report download. Cloud security standards enforce HTTPS.
        return "http://reports.resorts-internal.com:8080/download/" + reportName; // cr-java-0088
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);
        info.put("backupPath", BACKUP_PATH);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
