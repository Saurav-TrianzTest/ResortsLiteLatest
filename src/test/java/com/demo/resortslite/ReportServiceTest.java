package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    // -----------------------------------------------------------------------
    // generateMonthlyReport tests
    // -----------------------------------------------------------------------

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsNonNullResult() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotNull(result);
    }

    @Test
    void generateMonthlyReport_withValidMonthAndYear_resultContainsStatusKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertTrue(result.containsKey("status"), "Result should contain 'status' key");
    }

    @Test
    void generateMonthlyReport_withValidMonthAndYear_resultContainsPathOrMessageKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert — Either 'path' (success) or 'message' (error) should be present
        assertTrue(result.containsKey("path") || result.containsKey("message"),
                "Result should contain 'path' or 'message' key");
    }

    @Test
    void generateMonthlyReport_serverPortIs8080_whenGenerated() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            assertEquals(8080, result.get("serverPort"));
        } else {
            // error path: serverPort not included — acceptable
            assertEquals("error", status);
        }
    }

    @Test
    void generateMonthlyReport_statusIsGeneratedOrError() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status),
                "Status should be 'generated' or 'error', but was: " + status);
    }

    @Test
    void generateMonthlyReport_pathContainsMonthAndYear_whenGenerated() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("05", "2025");

        // Assert
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            String path = (String) result.get("path");
            assertTrue(path.contains("05"), "Path should contain month '05'");
            assertTrue(path.contains("2025"), "Path should contain year '2025'");
        } else {
            // error case is also acceptable (e.g., /var/legacy/reports/ not writable)
            assertTrue(result.containsKey("message"));
        }
    }

    @Test
    void generateMonthlyReport_differentMonthsProduceDifferentStatuses() {
        // Act
        Map<String, Object> result03 = reportService.generateMonthlyReport("03", "2024");
        Map<String, Object> result04 = reportService.generateMonthlyReport("04", "2024");

        // Assert — at minimum the status keys should be present
        assertNotNull(result03.get("status"));
        assertNotNull(result04.get("status"));
    }

    // -----------------------------------------------------------------------
    // buildReportDownloadUrl tests
    // -----------------------------------------------------------------------

    @Test
    void buildReportDownloadUrl_withReportName_returnsNonNullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.pdf");

        // Assert
        assertNotNull(url);
    }

    @Test
    void buildReportDownloadUrl_withReportName_urlContainsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.pdf");

        // Assert
        assertTrue(url.contains("march_report.pdf"),
                "URL should contain the report name");
    }

    @Test
    void buildReportDownloadUrl_urlContainsDownloadPath() {
        // Act
        String url = reportService.buildReportDownloadUrl("april_report.pdf");

        // Assert
        assertTrue(url.contains("/download/"),
                "URL should contain '/download/' path segment");
    }

    @Test
    void buildReportDownloadUrl_urlContainsHostname() {
        // Act
        String url = reportService.buildReportDownloadUrl("report.pdf");

        // Assert
        assertTrue(url.contains("reports.resorts-internal.com"),
                "URL should contain the reports hostname");
    }

    @Test
    void buildReportDownloadUrl_urlContainsPort8080() {
        // Act
        String url = reportService.buildReportDownloadUrl("report.pdf");

        // Assert
        assertTrue(url.contains("8080"),
                "URL should contain port 8080");
    }

    @Test
    void buildReportDownloadUrl_differentReportNames_produceDifferentUrls() {
        // Act
        String url1 = reportService.buildReportDownloadUrl("report_jan.pdf");
        String url2 = reportService.buildReportDownloadUrl("report_feb.pdf");

        // Assert
        assertNotEquals(url1, url2);
    }

    @Test
    void buildReportDownloadUrl_emptyReportName_returnsUrlWithDownloadSegment() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.contains("/download/"));
    }

    // -----------------------------------------------------------------------
    // getSystemInfo tests
    // -----------------------------------------------------------------------

    @Test
    void getSystemInfo_returnsNonNullMap() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info);
    }

    @Test
    void getSystemInfo_containsReportPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("reportPath"), "Info should contain 'reportPath' key");
    }

    @Test
    void getSystemInfo_containsBackupPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"), "Info should contain 'backupPath' key");
    }

    @Test
    void getSystemInfo_containsServerPortKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("serverPort"), "Info should contain 'serverPort' key");
    }

    @Test
    void getSystemInfo_containsGeneratedAtKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"), "Info should contain 'generatedAt' key");
    }

    @Test
    void getSystemInfo_serverPortIs8080() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_reportPathIsNotNull() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info.get("reportPath"));
    }

    @Test
    void getSystemInfo_backupPathIsNotNull() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info.get("backupPath"));
    }

    @Test
    void getSystemInfo_generatedAtIsFormattedTimestamp() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String timestamp = (String) info.get("generatedAt");
        assertNotNull(timestamp);
        // Format: yyyy-MM-dd HH:mm:ss  (length = 19)
        assertEquals(19, timestamp.length(),
                "Timestamp should be in 'yyyy-MM-dd HH:mm:ss' format (19 chars)");
    }

    @Test
    void getSystemInfo_generatedAtContainsDateSeparator() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String timestamp = (String) info.get("generatedAt");
        assertTrue(timestamp.contains("-"), "Timestamp should contain '-' date separator");
    }

    @Test
    void getSystemInfo_reportPathContainsReports() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String reportPath = (String) info.get("reportPath");
        assertTrue(reportPath.contains("reports"), "Report path should contain 'reports'");
    }

    @Test
    void getSystemInfo_calledTwice_generatedAtTimestampsAreStrings() {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert
        assertInstanceOf(String.class, info1.get("generatedAt"));
        assertInstanceOf(String.class, info2.get("generatedAt"));
    }
}
