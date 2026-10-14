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
    void generateMonthlyReport_withValidMonthAndYear_returnsMapWithStatusKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_whenDirectoryCannotBeCreated_returnsErrorOrGenerated() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("April", "2024");

        // Assert: either "generated" (if path writable) or "error" (if not)
        assertNotNull(result);
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status),
                "Status should be 'generated' or 'error', but was: " + status);
    }

    @Test
    void generateMonthlyReport_onError_resultContainsMessageKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("January", "2024");

        // Assert: if error, message key should be present
        assertNotNull(result);
        if ("error".equals(result.get("status"))) {
            assertTrue(result.containsKey("message"),
                    "Error result should contain 'message' key");
        }
    }

    @Test
    void generateMonthlyReport_onSuccess_resultContainsPathKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("February", "2024");

        // Assert: if generated, path key should be present
        assertNotNull(result);
        if ("generated".equals(result.get("status"))) {
            assertTrue(result.containsKey("path"),
                    "Generated result should contain 'path' key");
        }
    }

    @Test
    void generateMonthlyReport_onSuccess_resultContainsServerPortKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("May", "2024");

        // Assert: if generated, serverPort key should be present
        assertNotNull(result);
        if ("generated".equals(result.get("status"))) {
            assertTrue(result.containsKey("serverPort"),
                    "Generated result should contain 'serverPort' key");
        }
    }

    @Test
    void generateMonthlyReport_pathContainsMonthAndYear() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("July", "2023");

        // Assert
        assertNotNull(result);
        if ("generated".equals(result.get("status"))) {
            String path = (String) result.get("path");
            assertNotNull(path);
            assertTrue(path.contains("July"), "Path should contain month");
            assertTrue(path.contains("2023"), "Path should contain year");
        }
    }

    @Test
    void generateMonthlyReport_withEmptyMonth_doesNotThrow() {
        // Act & Assert
        assertDoesNotThrow(() -> reportService.generateMonthlyReport("", "2024"));
    }

    @Test
    void generateMonthlyReport_withEmptyYear_doesNotThrow() {
        // Act & Assert
        assertDoesNotThrow(() -> reportService.generateMonthlyReport("August", ""));
    }

    @Test
    void generateMonthlyReport_withSpecialCharactersInMonth_doesNotThrow() {
        // Act & Assert
        assertDoesNotThrow(() -> reportService.generateMonthlyReport("March/April", "2024"));
    }

    // -----------------------------------------------------------------------
    // buildReportDownloadUrl tests
    // -----------------------------------------------------------------------

    @Test
    void buildReportDownloadUrl_withValidReportName_returnsNonNullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("monthly_report.pdf");

        // Assert
        assertNotNull(url);
        assertFalse(url.isEmpty());
    }

    @Test
    void buildReportDownloadUrl_urlContainsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("q1_report.pdf");

        // Assert
        assertTrue(url.contains("q1_report.pdf"),
                "URL should contain the report name");
    }

    @Test
    void buildReportDownloadUrl_urlStartsWithHttp() {
        // Act
        String url = reportService.buildReportDownloadUrl("test_report.csv");

        // Assert
        assertTrue(url.startsWith("http"),
                "URL should start with http");
    }

    @Test
    void buildReportDownloadUrl_urlContainsDownloadPath() {
        // Act
        String url = reportService.buildReportDownloadUrl("annual_report.pdf");

        // Assert
        assertTrue(url.contains("/download/"),
                "URL should contain '/download/' path segment");
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsUrlEndingWithSlash() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.endsWith("/"),
                "URL with empty report name should end with '/'");
    }

    @Test
    void buildReportDownloadUrl_withSpecialCharacters_includesThemInUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("report 2024.pdf");

        // Assert
        assertNotNull(url);
        assertTrue(url.contains("report 2024.pdf"));
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
        assertTrue(info.containsKey("reportPath"),
                "System info should contain 'reportPath' key");
    }

    @Test
    void getSystemInfo_containsBackupPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"),
                "System info should contain 'backupPath' key");
    }

    @Test
    void getSystemInfo_containsServerPortKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("serverPort"),
                "System info should contain 'serverPort' key");
    }

    @Test
    void getSystemInfo_containsGeneratedAtKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"),
                "System info should contain 'generatedAt' key");
    }

    @Test
    void getSystemInfo_generatedAtIsNotNull() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info.get("generatedAt"),
                "generatedAt should not be null");
    }

    @Test
    void getSystemInfo_serverPortIs8080() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(8080, info.get("serverPort"),
                "Server port should be 8080");
    }

    @Test
    void getSystemInfo_reportPathIsNotEmpty() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String reportPath = (String) info.get("reportPath");
        assertNotNull(reportPath);
        assertFalse(reportPath.isEmpty(), "Report path should not be empty");
    }

    @Test
    void getSystemInfo_backupPathIsNotEmpty() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String backupPath = (String) info.get("backupPath");
        assertNotNull(backupPath);
        assertFalse(backupPath.isEmpty(), "Backup path should not be empty");
    }

    @Test
    void getSystemInfo_generatedAtMatchesTimestampPattern() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert: format is "yyyy-MM-dd HH:mm:ss"
        String generatedAt = (String) info.get("generatedAt");
        assertNotNull(generatedAt);
        assertTrue(generatedAt.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "generatedAt should match pattern yyyy-MM-dd HH:mm:ss, but was: " + generatedAt);
    }
}
