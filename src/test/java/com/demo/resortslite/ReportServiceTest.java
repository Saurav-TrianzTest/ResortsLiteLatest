package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ReportService}.
 */
class ReportServiceTest {

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    // -----------------------------------------------------------------------
    // generateMonthlyReport
    // -----------------------------------------------------------------------

    @Test
    void generateMonthlyReport_returnsNonNullMap() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotNull(result);
    }

    @Test
    void generateMonthlyReport_resultContainsStatusKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertTrue(result.containsKey("status"),
                "Result map should contain a 'status' key");
    }

    @Test
    void generateMonthlyReport_statusIsGeneratedOrError() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("05", "2024");

        // Assert – the method either succeeds or catches an IOException
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status),
                "Status should be 'generated' or 'error', but was: " + status);
    }

    @Test
    void generateMonthlyReport_whenSuccessful_containsPathKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("06", "2024");

        // Assert – if generated, path key must be present
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            assertTrue(result.containsKey("path"),
                    "Successful result should contain a 'path' key");
        }
    }

    @Test
    void generateMonthlyReport_whenSuccessful_pathContainsMonthAndYear() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("08", "2023");

        // Assert
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            String path = (String) result.get("path");
            assertTrue(path.contains("08"), "Path should contain the month");
            assertTrue(path.contains("2023"), "Path should contain the year");
        }
    }

    @Test
    void generateMonthlyReport_whenSuccessful_serverPortPresent() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("01", "2024");

        // Assert
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            assertTrue(result.containsKey("serverPort"),
                    "Successful result should contain 'serverPort'");
            assertEquals(8080, result.get("serverPort"));
        }
    }

    @Test
    void generateMonthlyReport_whenError_containsMessageKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("02", "2024");

        // Assert
        String status = (String) result.get("status");
        if ("error".equals(status)) {
            assertTrue(result.containsKey("message"),
                    "Error result should contain a 'message' key");
        }
    }

    @Test
    void generateMonthlyReport_differentMonthsProduceDifferentPaths() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("01", "2024");
        Map<String, Object> result2 = reportService.generateMonthlyReport("12", "2024");

        // Assert – if both succeed, paths should differ
        if ("generated".equals(result1.get("status")) && "generated".equals(result2.get("status"))) {
            assertNotEquals(result1.get("path"), result2.get("path"),
                    "Different months should produce different file paths");
        }
    }

    // -----------------------------------------------------------------------
    // buildReportDownloadUrl
    // -----------------------------------------------------------------------

    @Test
    void buildReportDownloadUrl_returnsNonNullString() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.pdf");

        // Assert
        assertNotNull(url);
    }

    @Test
    void buildReportDownloadUrl_containsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.pdf");

        // Assert
        assertTrue(url.contains("march_report.pdf"),
                "URL should contain the report name");
    }

    @Test
    void buildReportDownloadUrl_startsWithHttp() {
        // Act
        String url = reportService.buildReportDownloadUrl("report.csv");

        // Assert
        assertTrue(url.startsWith("http"),
                "URL should start with 'http'");
    }

    @Test
    void buildReportDownloadUrl_containsDownloadPath() {
        // Act
        String url = reportService.buildReportDownloadUrl("annual_report.pdf");

        // Assert
        assertTrue(url.contains("/download/"),
                "URL should contain '/download/' path segment");
    }

    @Test
    void buildReportDownloadUrl_differentNamesProduceDifferentUrls() {
        // Act
        String url1 = reportService.buildReportDownloadUrl("report_jan.pdf");
        String url2 = reportService.buildReportDownloadUrl("report_feb.pdf");

        // Assert
        assertNotEquals(url1, url2,
                "Different report names should produce different URLs");
    }

    @Test
    void buildReportDownloadUrl_emptyNameStillReturnsUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertFalse(url.isEmpty());
    }

    // -----------------------------------------------------------------------
    // getSystemInfo
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
                "System info should contain 'reportPath'");
    }

    @Test
    void getSystemInfo_containsBackupPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"),
                "System info should contain 'backupPath'");
    }

    @Test
    void getSystemInfo_containsServerPortKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("serverPort"),
                "System info should contain 'serverPort'");
    }

    @Test
    void getSystemInfo_serverPortIs8080() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_containsGeneratedAtKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"),
                "System info should contain 'generatedAt'");
    }

    @Test
    void getSystemInfo_generatedAtIsNonEmpty() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String ts = (String) info.get("generatedAt");
        assertNotNull(ts);
        assertFalse(ts.isEmpty(), "generatedAt timestamp should not be empty");
    }

    @Test
    void getSystemInfo_generatedAtMatchesDateTimePattern() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert – format: yyyy-MM-dd HH:mm:ss  (19 chars)
        String ts = (String) info.get("generatedAt");
        assertNotNull(ts);
        assertEquals(19, ts.length(),
                "Timestamp should be 19 characters (yyyy-MM-dd HH:mm:ss)");
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
}
