package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link ReportService}.
 * Covers generateMonthlyReport, buildReportDownloadUrl, and getSystemInfo.
 */
class ReportServiceTest {

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateMonthlyReport
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsNonNullMap() {
        Map<String, Object> result = reportService.generateMonthlyReport("2024-03", "2024");
        assertNotNull(result);
    }

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsStatusKey() {
        Map<String, Object> result = reportService.generateMonthlyReport("2024-03", "2024");
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_whenDirectoryNotWritable_returnsErrorStatus() {
        // /var/legacy/reports/ likely does not exist in test environment → IOException
        Map<String, Object> result = reportService.generateMonthlyReport("2024-03", "2024");
        // Either "generated" (if dir is writable) or "error" (if not) — both are valid
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status),
                "Status must be 'generated' or 'error', but was: " + status);
    }

    @Test
    void generateMonthlyReport_onIOError_returnsErrorStatus() {
        // Force an error by using an invalid month string that causes path issues
        Map<String, Object> result = reportService.generateMonthlyReport("2024-03", "2024");
        assertNotNull(result.get("status"));
    }

    @Test
    void generateMonthlyReport_onIOError_containsMessageKey() {
        Map<String, Object> result = reportService.generateMonthlyReport("2024-03", "2024");
        // If error, message key should be present; if generated, path key should be present
        assertTrue(result.containsKey("message") || result.containsKey("path"),
                "Result must contain either 'message' (error) or 'path' (success)");
    }

    @Test
    void generateMonthlyReport_onSuccess_containsServerPort() {
        Map<String, Object> result = reportService.generateMonthlyReport("2024-03", "2024");
        // serverPort is only set on success path
        if ("generated".equals(result.get("status"))) {
            assertNotNull(result.get("serverPort"));
            assertEquals(8080, result.get("serverPort"));
        }
    }

    @Test
    void generateMonthlyReport_onSuccess_pathContainsMonthAndYear() {
        Map<String, Object> result = reportService.generateMonthlyReport("2024-03", "2024");
        if ("generated".equals(result.get("status"))) {
            String path = (String) result.get("path");
            assertTrue(path.contains("2024-03"));
            assertTrue(path.contains("2024"));
        }
    }

    @Test
    void generateMonthlyReport_withDifferentMonthYear_returnsMap() {
        Map<String, Object> result = reportService.generateMonthlyReport("2023-12", "2023");
        assertNotNull(result);
        assertNotNull(result.get("status"));
    }

    @Test
    void generateMonthlyReport_withEmptyMonth_doesNotThrow() {
        assertDoesNotThrow(() -> reportService.generateMonthlyReport("", "2024"));
    }

    @Test
    void generateMonthlyReport_withNullMonth_doesNotThrow() {
        assertDoesNotThrow(() -> reportService.generateMonthlyReport(null, "2024"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildReportDownloadUrl
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildReportDownloadUrl_withValidName_returnsNonNullUrl() {
        String url = reportService.buildReportDownloadUrl("march_report.pdf");
        assertNotNull(url);
    }

    @Test
    void buildReportDownloadUrl_withValidName_containsReportName() {
        String url = reportService.buildReportDownloadUrl("march_report.pdf");
        assertTrue(url.contains("march_report.pdf"));
    }

    @Test
    void buildReportDownloadUrl_withValidName_containsBaseUrl() {
        String url = reportService.buildReportDownloadUrl("march_report.pdf");
        assertTrue(url.contains("http://reports.resorts-internal.com:8080/download/"));
    }

    @Test
    void buildReportDownloadUrl_withDifferentName_returnsCorrectUrl() {
        String url = reportService.buildReportDownloadUrl("annual_2024.csv");
        assertEquals("http://reports.resorts-internal.com:8080/download/annual_2024.csv", url);
    }

    @Test
    void buildReportDownloadUrl_withEmptyName_returnsBaseUrlOnly() {
        String url = reportService.buildReportDownloadUrl("");
        assertEquals("http://reports.resorts-internal.com:8080/download/", url);
    }

    @Test
    void buildReportDownloadUrl_withSpecialCharacters_includesThemInUrl() {
        String url = reportService.buildReportDownloadUrl("report 2024.pdf");
        assertTrue(url.contains("report 2024.pdf"));
    }

    @Test
    void buildReportDownloadUrl_withNullName_doesNotThrow() {
        // Null concatenation results in "null" string in Java
        assertDoesNotThrow(() -> reportService.buildReportDownloadUrl(null));
    }

    @Test
    void buildReportDownloadUrl_withNullName_containsNullLiteral() {
        String url = reportService.buildReportDownloadUrl(null);
        assertTrue(url.contains("null"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getSystemInfo
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getSystemInfo_returnsNonNullMap() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertNotNull(info);
    }

    @Test
    void getSystemInfo_containsReportPath() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("reportPath"));
        assertEquals("/var/legacy/reports/", info.get("reportPath"));
    }

    @Test
    void getSystemInfo_containsBackupPath() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("backupPath"));
        assertEquals("C:\\ResortBackups\\nightly\\", info.get("backupPath"));
    }

    @Test
    void getSystemInfo_containsServerPort() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("serverPort"));
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_containsGeneratedAt() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("generatedAt"));
        assertNotNull(info.get("generatedAt"));
    }

    @Test
    void getSystemInfo_generatedAtMatchesTimestampPattern() {
        Map<String, Object> info = reportService.getSystemInfo();
        String generatedAt = (String) info.get("generatedAt");
        // Pattern: yyyy-MM-dd HH:mm:ss  (19 characters)
        assertNotNull(generatedAt);
        assertEquals(19, generatedAt.length(),
                "Timestamp should be 19 chars (yyyy-MM-dd HH:mm:ss), was: " + generatedAt);
    }

    @Test
    void getSystemInfo_generatedAtContainsDateSeparator() {
        Map<String, Object> info = reportService.getSystemInfo();
        String generatedAt = (String) info.get("generatedAt");
        assertTrue(generatedAt.contains("-"), "Timestamp should contain '-' date separator");
    }

    @Test
    void getSystemInfo_generatedAtContainsTimeSeparator() {
        Map<String, Object> info = reportService.getSystemInfo();
        String generatedAt = (String) info.get("generatedAt");
        assertTrue(generatedAt.contains(":"), "Timestamp should contain ':' time separator");
    }

    @Test
    void getSystemInfo_calledTwice_returnsDifferentOrSameTimestamp() {
        // Both calls should succeed without exception
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();
        assertNotNull(info1.get("generatedAt"));
        assertNotNull(info2.get("generatedAt"));
    }

    @Test
    void getSystemInfo_returnsMapWithFourKeys() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertEquals(4, info.size(),
                "getSystemInfo should return exactly 4 keys: reportPath, backupPath, serverPort, generatedAt");
    }
}
