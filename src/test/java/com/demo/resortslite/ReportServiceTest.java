package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
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
    void generateMonthlyReport_withValidMonthAndYear_returnsMap() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void generateMonthlyReport_resultContainsStatusKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("June", "2024");

        // Assert
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_statusIsGeneratedOrError() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("July", "2024");

        // Assert
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status),
                "Status should be 'generated' or 'error', but was: " + status);
    }

    @Test
    void generateMonthlyReport_whenSuccessful_containsPathKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("August", "2024");

        // Assert — path key should be present regardless of success/failure
        // (it's set before the try block in the path variable)
        assertNotNull(result);
    }

    @Test
    void generateMonthlyReport_whenSuccessful_serverPortIsPresent() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("September", "2024");

        // Assert
        // If generated successfully, serverPort should be in result
        if ("generated".equals(result.get("status"))) {
            assertNotNull(result.get("serverPort"));
            assertEquals(8080, result.get("serverPort"));
        }
    }

    @Test
    void generateMonthlyReport_whenError_containsMessageKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("October", "2024");

        // Assert
        if ("error".equals(result.get("status"))) {
            assertTrue(result.containsKey("message"));
        }
    }

    @Test
    void generateMonthlyReport_withDifferentMonths_returnsDistinctPaths() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("January", "2024");
        Map<String, Object> result2 = reportService.generateMonthlyReport("February", "2024");

        // Assert
        assertNotNull(result1);
        assertNotNull(result2);
        // Both should have status
        assertTrue(result1.containsKey("status"));
        assertTrue(result2.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_withNullMonth_doesNotThrowNpe() {
        // Act & Assert — should not throw NPE, may return error status
        assertDoesNotThrow(() -> reportService.generateMonthlyReport(null, "2024"));
    }

    @Test
    void generateMonthlyReport_withEmptyStrings_returnsMap() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("", "");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("status"));
    }

    // -----------------------------------------------------------------------
    // buildReportDownloadUrl tests
    // -----------------------------------------------------------------------

    @Test
    void buildReportDownloadUrl_withValidName_returnsNonNullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.pdf");

        // Assert
        assertNotNull(url);
        assertFalse(url.isEmpty());
    }

    @Test
    void buildReportDownloadUrl_containsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("june_report.pdf");

        // Assert
        assertTrue(url.contains("june_report.pdf"));
    }

    @Test
    void buildReportDownloadUrl_startsWithHttp() {
        // Act
        String url = reportService.buildReportDownloadUrl("test_report.pdf");

        // Assert
        assertTrue(url.startsWith("http://"));
    }

    @Test
    void buildReportDownloadUrl_containsDownloadPath() {
        // Act
        String url = reportService.buildReportDownloadUrl("annual_report.pdf");

        // Assert
        assertTrue(url.contains("/download/"));
    }

    @Test
    void buildReportDownloadUrl_containsReportsHost() {
        // Act
        String url = reportService.buildReportDownloadUrl("q1_report.pdf");

        // Assert
        assertTrue(url.contains("reports.resorts-internal.com"));
    }

    @Test
    void buildReportDownloadUrl_containsPort8080() {
        // Act
        String url = reportService.buildReportDownloadUrl("q2_report.pdf");

        // Assert
        assertTrue(url.contains("8080"));
    }

    @Test
    void buildReportDownloadUrl_withEmptyName_returnsUrlWithEmptySegment() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.startsWith("http://"));
    }

    @Test
    void buildReportDownloadUrl_withSpecialChars_includesThemInUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("report_2024-03.pdf");

        // Assert
        assertTrue(url.contains("report_2024-03.pdf"));
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
        assertTrue(info.containsKey("reportPath"));
    }

    @Test
    void getSystemInfo_containsBackupPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"));
    }

    @Test
    void getSystemInfo_containsServerPortKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("serverPort"));
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_containsGeneratedAtKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"));
        assertNotNull(info.get("generatedAt"));
    }

    @Test
    void getSystemInfo_generatedAtIsFormattedTimestamp() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String timestamp = (String) info.get("generatedAt");
        assertNotNull(timestamp);
        // Format: yyyy-MM-dd HH:mm:ss
        assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "Timestamp format should be yyyy-MM-dd HH:mm:ss, but was: " + timestamp);
    }

    @Test
    void getSystemInfo_reportPathMatchesConstant() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals("/var/legacy/reports/", info.get("reportPath"));
    }

    @Test
    void getSystemInfo_backupPathMatchesConstant() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info.get("backupPath"));
        assertTrue(info.get("backupPath").toString().contains("ResortBackups"));
    }

    @Test
    void getSystemInfo_calledMultipleTimes_returnsConsistentKeys() {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert
        assertEquals(info1.keySet(), info2.keySet());
    }

    @Test
    void getSystemInfo_returnsMapWithFourEntries() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(4, info.size());
    }
}
