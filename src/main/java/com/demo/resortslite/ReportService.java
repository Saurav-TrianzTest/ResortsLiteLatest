package com.demo.resortslite;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native report generation using Azure Blob Storage.
 *
 * <p>cr-java-0063 FIX: All {@code java.io.File} usages for persistent data storage have been
 * replaced with Azure Blob Storage operations via the Azure SDK for Java.
 * <ul>
 *   <li>Removed {@code import java.io.File} and {@code import java.io.FileWriter}</li>
 *   <li>Removed hard-coded path constants {@code REPORT_BASE_PATH} and {@code BACKUP_PATH}</li>
 *   <li>Line 37 – {@code fullPath = REPORT_BASE_PATH + fileName} replaced by blob name</li>
 *   <li>Line 39 – {@code new File(REPORT_BASE_PATH)} replaced by {@code BlobContainerClient}</li>
 *   <li>Line 42 – {@code reportDir.mkdirs()} replaced by {@code containerClient.createIfNotExists()}</li>
 * </ul>
 * Azure Blob Storage container names are externalised via application properties /
 * environment variables, following 12-factor app principles.
 * </p>
 *
 * <p>cr-java-0111 FIX: Server-local timezone and {@code java.util.Timer} scheduling replaced
 * with Azure Service Bus Scheduled Messages for distributed, timezone-agnostic task execution.
 * <ul>
 *   <li>Removed {@code import java.text.SimpleDateFormat} and {@code import java.util.Date}</li>
 *   <li>Replaced {@code new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())} with
 *       {@code Instant.now(Clock.systemUTC())} formatted via {@link DateTimeFormatter#ISO_INSTANT}
 *       — always UTC, never dependent on the JVM/container default timezone.</li>
 *   <li>Scheduled report generation tasks are dispatched as Azure Service Bus scheduled messages
 *       rather than relying on {@code java.util.Timer} or server-local cron, ensuring reliable
 *       distributed scheduling across all cloud instances and regions.</li>
 * </ul>
 * </p>
 */
@Service
public class ReportService {

    // cr-java-0063 FIX: Azure Blob Storage connection string injected from environment variable
    // AZURE_STORAGE_CONNECTION_STRING — no hard-coded credentials or file-system paths.
    @Value("${azure.storage.connection-string}")
    private String azureStorageConnectionString;

    // cr-java-0063 FIX: Reports container name externalised via application property
    // (azure.storage.reports-container). Replaces hard-coded REPORT_BASE_PATH constant.
    @Value("${azure.storage.reports-container:resort-reports}")
    private String reportsContainerName;

    // cr-java-0063 FIX: Backup container name externalised via application property
    // (azure.storage.backup-container). Replaces hard-coded BACKUP_PATH constant.
    @Value("${azure.storage.backup-container:resort-backups}")
    private String backupContainerName;

    // cr-java-0071 FIX: Hard-coded environment URL externalised to Azure App Configuration.
    // The report download base URL is now injected from the 'app.reports.download.base-url'
    // property, which is resolved from environment variables / Azure App Configuration at runtime.
    @Value("${app.reports.download.base-url:${REPORTS_DOWNLOAD_BASE_URL:https://reports.resorts-internal.com/download}}")
    private String reportsDownloadBaseUrl;

    // cr-java-0077 FIX: Hard-coded SERVER_PORT constant removed.
    // Port is now externalised to Azure App Configuration / environment variable SERVER_PORT,
    // enabling dynamic port assignment by Azure container orchestration platforms.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0111 FIX: Azure Service Bus connection string injected from environment variable
    // AZURE_SERVICE_BUS_CONNECTION_STRING — used for distributed scheduled message delivery
    // instead of java.util.Timer or server-local scheduling.
    @Value("${azure.servicebus.connection-string:${AZURE_SERVICE_BUS_CONNECTION_STRING:}}")
    private String serviceBusConnectionString;

    // cr-java-0111 FIX: Azure Service Bus queue name for scheduled report tasks.
    // Externalised via application property azure.servicebus.report-queue-name.
    @Value("${azure.servicebus.report-queue-name:resort-report-tasks}")
    private String reportQueueName;

    /**
     * Generates a monthly report CSV and uploads it to Azure Blob Storage.
     *
     * <p>cr-java-0063 FIX (Lines 37, 39, 42 of original source):
     * <ul>
     *   <li>Line 37 – {@code String fullPath = REPORT_BASE_PATH + fileName} removed;
     *       the blob name ({@code fileName}) is used directly as the Azure Blob name.</li>
     *   <li>Line 39 – {@code File reportDir = new File(REPORT_BASE_PATH)} removed;
     *       replaced by {@link BlobContainerClient} obtained from Azure SDK.</li>
     *   <li>Line 42 – {@code reportDir.mkdirs()} removed;
     *       replaced by {@code containerClient.createIfNotExists()} which creates the
     *       Azure Blob container if it does not already exist.</li>
     * </ul>
     * CSV content is built in-memory and uploaded directly to Azure Blob Storage —
     * no local file-system writes occur.
     * </p>
     *
     * @param month the month for which the report is generated (e.g. "03")
     * @param year  the year for which the report is generated (e.g. "2024")
     * @return a map containing the operation status and the Azure Blob URL of the report
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";

        // cr-java-0063 FIX (original Line 37): 'fullPath = REPORT_BASE_PATH + fileName' replaced.
        // The blob name IS the logical "path" within the Azure Blob container — no local FS path needed.
        String blobName = fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build Azure Blob Storage client from externalised connection string
            BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                    .connectionString(azureStorageConnectionString)
                    .buildClient();

            // cr-java-0063 FIX (original Line 39): 'new File(REPORT_BASE_PATH)' replaced.
            // Obtain a BlobContainerClient for the reports container via Azure SDK.
            BlobContainerClient containerClient = blobServiceClient
                    .getBlobContainerClient(reportsContainerName);

            // cr-java-0063 FIX (original Line 42): 'reportDir.mkdirs()' replaced.
            // Azure SDK creates the Blob container automatically if it does not already exist.
            containerClient.createIfNotExists();

            // Build CSV content in memory — no local file-system writes
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            byte[] csvBytes = csvContent.getBytes(StandardCharsets.UTF_8);

            // Upload the CSV bytes directly to Azure Blob Storage
            BlobClient blobClient = containerClient.getBlobClient(blobName);
            blobClient.upload(new ByteArrayInputStream(csvBytes), csvBytes.length, true);

            result.put("status", "generated");
            result.put("blobUrl", blobClient.getBlobUrl());
            result.put("serverPort", serverPort); // cr-java-0077 FIX: injected from environment

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Schedules a report generation task using Azure Service Bus Scheduled Messages.
     *
     * <p>cr-java-0111 FIX: Replaces {@code java.util.Timer} / server-local scheduling with
     * Azure Service Bus scheduled message delivery. The message is enqueued with a UTC-based
     * {@link OffsetDateTime} delivery time, ensuring timezone-agnostic, distributed scheduling
     * across all cloud instances and regions. No server-local clock or JVM timezone is used.</p>
     *
     * @param month          the report month (e.g. "03")
     * @param year           the report year (e.g. "2024")
     * @param delayInSeconds number of seconds from now (UTC) at which the message should be delivered
     * @return a map containing the scheduling status and the scheduled enqueue time (UTC)
     */
    public Map<String, Object> scheduleReportGeneration(String month, String year, long delayInSeconds) {
        Map<String, Object> result = new HashMap<>();

        // cr-java-0111 FIX: Use UTC clock (Clock.systemUTC()) instead of server-local timezone.
        // OffsetDateTime.now(ZoneOffset.UTC) is timezone-agnostic and safe across all cloud regions.
        OffsetDateTime scheduledEnqueueTime = OffsetDateTime.now(ZoneOffset.UTC)
                .plusSeconds(delayInSeconds);

        String messageBody = String.format(
                "{\"action\":\"generateMonthlyReport\",\"month\":\"%s\",\"year\":\"%s\"}",
                month, year);

        try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(reportQueueName)
                .buildClient()) {

            ServiceBusMessage message = new ServiceBusMessage(messageBody);
            // cr-java-0111 FIX: scheduleMessage uses UTC OffsetDateTime — no server-local timer.
            long sequenceNumber = senderClient.scheduleMessage(message, scheduledEnqueueTime);

            result.put("status", "scheduled");
            result.put("sequenceNumber", sequenceNumber);
            // cr-java-0111 FIX: Timestamp formatted as ISO-8601 UTC — no SimpleDateFormat / Date.
            result.put("scheduledEnqueueTimeUtc",
                    scheduledEnqueueTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // cr-java-0071 FIX: Hard-coded environment URL replaced with value injected from
        // Azure App Configuration via 'app.reports.download.base-url' property /
        // REPORTS_DOWNLOAD_BASE_URL environment variable.
        return reportsDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns system information including Azure Blob Storage container references.
     *
     * <p>cr-java-0063 FIX: {@code REPORT_BASE_PATH} and {@code BACKUP_PATH} constants removed;
     * container names sourced from externalised configuration are reported instead.</p>
     *
     * <p>cr-java-0111 FIX: {@code new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())}
     * replaced with {@code Instant.now(Clock.systemUTC())} formatted via
     * {@link DateTimeFormatter#ISO_INSTANT}. This eliminates the server-local timezone dependency:
     * the timestamp is always expressed in UTC regardless of the JVM default timezone or the
     * container/region in which the application is running.</p>
     *
     * @return a map of system metadata
     */
    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // cr-java-0111 FIX (original Line 70): 'new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())'
        // replaced with UTC-based java.time API. Clock.systemUTC() ensures the timestamp is
        // timezone-agnostic and consistent across all cloud instances and regions.
        // Removed: import java.text.SimpleDateFormat and import java.util.Date
        String timestamp = Instant.now(Clock.systemUTC())
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));

        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 FIX: replaced hard-coded REPORT_BASE_PATH / BACKUP_PATH with
        // Azure Blob Storage container names from externalised configuration.
        info.put("reportsContainer", reportsContainerName);   // was: REPORT_BASE_PATH
        info.put("backupContainer", backupContainerName);     // was: BACKUP_PATH
        info.put("serverPort", serverPort);                   // cr-java-0077 FIX: injected from environment
        info.put("generatedAt", timestamp);                   // cr-java-0111 FIX: UTC timestamp
        info.put("reportQueueName", reportQueueName);         // cr-java-0111 FIX: Service Bus queue
        return info;
    }
}
