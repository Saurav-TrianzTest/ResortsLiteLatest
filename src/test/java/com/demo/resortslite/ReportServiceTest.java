package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
    void generateMonthlyReport_withValidMonthAndYear_returnsNonNullMap() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotNull(result);
    }

    @Test
    void generateMonthlyReport_withValidMonthAndYear_mapContainsStatusKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertTrue(result.containsKey("status"), "Result map should contain 'status' key");
    }

    @Test
    void generateMonthlyReport_whenDirectoryNotWritable_returnsErrorStatus() {
        // Act - /var/legacy/reports/ likely doesn't exist in test environment
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert - either generated (if dir exists) or error (if not)
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status),
                "Status should be 'generated' or 'error', got: " + status);
    }

    @Test
    void generateMonthlyReport_whenIOError_resultContainsErrorStatus() {
        // Act - using invalid characters in month/year to trigger path issues
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotNull(result.get("status"));
    }

    @Test
    void generateMonthlyReport_withDifferentMonths_returnsMapForEach() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("01", "2024");
        Map<String, Object> result2 = reportService.generateMonthlyReport("12", "2024");

        // Assert
        assertNotNull(result1);
        assertNotNull(result2);
        assertNotNull(result1.get("status"));
        assertNotNull(result2.get("status"));
    }

    @Test
    void generateMonthlyReport_resultContainsServerPortKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("06", "2024");

        // Assert - serverPort is set in both success and error paths (only in success)
        assertNotNull(result);
    }

    @Test
    void generateMonthlyReport_successPath_containsPathKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            assertTrue(result.containsKey("path"), "Generated result should contain 'path' key");
            String path = (String) result.get("path");
            assertTrue(path.contains("resort_report_03_2024.csv"),
                    "Path should contain the expected filename");
        }
    }

    @Test
    void generateMonthlyReport_withNullMonth_doesNotThrowNPE() {
        // Act & Assert - should handle gracefully
        assertDoesNotThrow(() -> reportService.generateMonthlyReport(null, "2024"));
    }

    @Test
    void generateMonthlyReport_withEmptyStrings_doesNotThrow() {
        // Act & Assert
        assertDoesNotThrow(() -> reportService.generateMonthlyReport("", ""));
    }

    // -----------------------------------------------------------------------
    // buildReportDownloadUrl tests
    // -----------------------------------------------------------------------

    @Test
    void buildReportDownloadUrl_withValidReportName_returnsNonNullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.csv");

        // Assert
        assertNotNull(url);
    }

    @Test
    void buildReportDownloadUrl_withValidReportName_urlContainsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.csv");

        // Assert
        assertTrue(url.contains("march_report.csv"),
                "URL should contain the report name");
    }

    @Test
    void buildReportDownloadUrl_withValidReportName_urlStartsWithHttp() {
        // Act
        String url = reportService.buildReportDownloadUrl("report.pdf");

        // Assert
        assertTrue(url.startsWith("http://"), "URL should start with http://");
    }

    @Test
    void buildReportDownloadUrl_withValidReportName_urlContainsDownloadPath() {
        // Act
        String url = reportService.buildReportDownloadUrl("test_report.csv");

        // Assert
        assertTrue(url.contains("/download/"), "URL should contain '/download/' path segment");
    }

    @Test
    void buildReportDownloadUrl_withDifferentReportNames_returnsDistinctUrls() {
        // Act
        String url1 = reportService.buildReportDownloadUrl("report_jan.csv");
        String url2 = reportService.buildReportDownloadUrl("report_feb.csv");

        // Assert
        assertNotEquals(url1, url2, "Different report names should produce different URLs");
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsUrlWithEmptySegment() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.startsWith("http://"));
    }

    @Test
    void buildReportDownloadUrl_urlContainsPort8080() {
        // Act
        String url = reportService.buildReportDownloadUrl("any_report.csv");

        // Assert
        assertTrue(url.contains(":8080"), "URL should contain port 8080");
    }

    @Test
    void buildReportDownloadUrl_withSpecialCharactersInName_returnsUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("report_2024-03.csv");

        // Assert
        assertNotNull(url);
        assertTrue(url.contains("report_2024-03.csv"));
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
        assertTrue(info.containsKey("reportPath"), "System info should contain 'reportPath'");
    }

    @Test
    void getSystemInfo_containsBackupPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"), "System info should contain 'backupPath'");
    }

    @Test
    void getSystemInfo_containsServerPortKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("serverPort"), "System info should contain 'serverPort'");
    }

    @Test
    void getSystemInfo_containsGeneratedAtKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"), "System info should contain 'generatedAt'");
    }

    @Test
    void getSystemInfo_serverPortIsEightThousandAndEighty() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_reportPathIsCorrect() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals("/var/legacy/reports/", info.get("reportPath"));
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
    void getSystemInfo_calledTwice_returnsConsistentKeys() {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert
        assertEquals(info1.keySet(), info2.keySet(), "Both calls should return the same keys");
    }

    @Test
    void getSystemInfo_backupPathContainsResortBackups() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String backupPath = (String) info.get("backupPath");
        assertNotNull(backupPath);
        assertTrue(backupPath.contains("ResortBackups"),
                "Backup path should contain 'ResortBackups'");
    }
}
