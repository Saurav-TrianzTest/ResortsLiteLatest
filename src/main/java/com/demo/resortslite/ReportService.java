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
import java.io.InputStream;
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
 * <p>Remediation cr-java-0063: All java.io.File-based persistent storage operations
 * (new File, mkdirs, FileWriter) have been replaced with Azure Blob Storage using the
 * Azure SDK for Java to ensure data durability, scalability, and cloud-native compliance.</p>
 *
 * <p>Previously the service wrote reports to hard-coded local paths:
 * <ul>
 *   <li>{@code /var/legacy/reports/} — Linux absolute path (unavailable in containers)</li>
 *   <li>{@code C:\ResortBackups\nightly\} — Windows absolute path (OS-specific)</li>
 * </ul>
 * These paths are replaced by Azure Blob Storage containers configured via environment
 * variables ({@code AZURE_STORAGE_CONNECTION_STRING}, {@code AZURE_STORAGE_REPORTS_CONTAINER},
 * {@code AZURE_STORAGE_BACKUP_CONTAINER}).</p>
 *
 * <p>Remediation cr-java-0071: Hard-coded environment URL for report downloads replaced
 * with a value injected from Azure App Configuration via the {@code app.reports.download-base-url}
 * property, backed by the {@code APP_REPORTS_DOWNLOAD_BASE_URL} environment variable.</p>
 *
 * <p>Remediation cr-java-0111: Server-local timezone dependency ({@code SimpleDateFormat} +
 * {@code new Date()}) and any local scheduling via {@code java.util.Timer} replaced with:
 * <ul>
 *   <li>UTC-based timestamps using {@code Instant.now(Clock.systemUTC())} and
 *       {@code DateTimeFormatter.ISO_OFFSET_DATE_TIME} — timezone-agnostic across all
 *       cloud regions and container instances.</li>
 *   <li>Azure Service Bus scheduled message delivery for distributed, timezone-agnostic
 *       task execution. Report generation tasks are enqueued as scheduled messages on the
 *       Azure Service Bus queue configured via {@code AZURE_SERVICEBUS_CONNECTION_STRING}
 *       and {@code AZURE_SERVICEBUS_QUEUE_NAME}, eliminating reliance on server-local
 *       {@code java.util.Timer} or cron-based scheduling.</li>
 * </ul>
 * </p>
 */
@Service
public class ReportService {

    /**
     * Azure Blob Storage connection string — injected from the
     * {@code AZURE_STORAGE_CONNECTION_STRING} environment variable via
     * {@code azure.storage.connection-string} in application.properties.
     *
     * <p>Replaces hard-coded absolute file paths (cr-java-0062 / cr-java-0063):
     * was {@code private static final String REPORT_BASE_PATH = "/var/legacy/reports/";} and
     * {@code private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\";}</p>
     */
    @Value("${azure.storage.connection-string}")
    private String storageConnectionString;

    /**
     * Azure Blob Storage container name for reports.
     * Defaults to {@code reports} if {@code AZURE_STORAGE_REPORTS_CONTAINER} is not set.
     */
    @Value("${azure.storage.reports.container-name:reports}")
    private String reportsContainerName;

    /**
     * Azure Blob Storage container name for backups.
     * Defaults to {@code backups} if {@code AZURE_STORAGE_BACKUP_CONTAINER} is not set.
     */
    @Value("${azure.storage.backup.container-name:backups}")
    private String backupContainerName;

    /**
     * Server port — managed externally via {@code server.port} property or environment variable.
     * Replaces: {@code private static final int SERVER_PORT = 8080;}
     *
     * <p>Remediation cr-java-0077: Hard-coded port {@code 8080} replaced with a value injected
     * from Azure App Configuration / environment variable {@code SERVER_PORT}. Set the
     * {@code SERVER_PORT} environment variable in Azure App Service or Container Apps to allow
     * dynamic port assignment by the container orchestration platform.</p>
     */
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    /**
     * Remediation cr-java-0071: Base URL for report downloads, externalized to Azure App
     * Configuration via the {@code app.reports.download-base-url} property.
     * Set the {@code APP_REPORTS_DOWNLOAD_BASE_URL} environment variable to supply the
     * correct endpoint per deployment environment.
     *
     * <p>Was (line 66 source): {@code return "http://reports.resorts-internal.com:8080/download/" + reportName;}</p>
     */
    @Value("${app.reports.download-base-url:${APP_REPORTS_DOWNLOAD_BASE_URL:}}")
    private String reportsDownloadBaseUrl;

    /**
     * Azure Service Bus connection string — injected from the
     * {@code AZURE_SERVICEBUS_CONNECTION_STRING} environment variable via
     * {@code azure.servicebus.connection-string} in application.properties.
     *
     * <p>Remediation cr-java-0111: Replaces server-local {@code java.util.Timer} scheduling.
     * Report generation tasks are dispatched as scheduled messages on the Azure Service Bus
     * queue, enabling distributed, timezone-agnostic task execution across all cloud regions
     * and container instances.</p>
     */
    @Value("${azure.servicebus.connection-string:${AZURE_SERVICEBUS_CONNECTION_STRING:}}")
    private String serviceBusConnectionString;

    /**
     * Azure Service Bus queue name for scheduled report generation tasks.
     * Defaults to {@code report-tasks} if {@code AZURE_SERVICEBUS_QUEUE_NAME} is not set.
     *
     * <p>Remediation cr-java-0111: Replaces server-local timer-based scheduling with
     * Azure Service Bus scheduled message delivery.</p>
     */
    @Value("${azure.servicebus.queue-name:${AZURE_SERVICEBUS_QUEUE_NAME:report-tasks}}")
    private String serviceBusQueueName;

    /**
     * UTC-based clock used for all timestamp generation.
     *
     * <p>Remediation cr-java-0111: Replaces server-local timezone dependency
     * ({@code SimpleDateFormat} + {@code new Date()}) with an explicit UTC clock
     * ({@code Clock.systemUTC()}) to ensure consistent, timezone-agnostic timestamps
     * across all cloud regions and container instances.</p>
     */
    private final Clock utcClock = Clock.systemUTC();

    /**
     * ISO-8601 formatter for UTC timestamps — timezone-agnostic across all cloud regions.
     *
     * <p>Remediation cr-java-0111: Replaces {@code new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")}
     * which inherits the server JVM's default timezone. Using {@code DateTimeFormatter.ISO_OFFSET_DATE_TIME}
     * with an explicit {@code ZoneOffset.UTC} offset ensures all timestamps are unambiguous
     * regardless of the host timezone setting.</p>
     */
    private static final DateTimeFormatter UTC_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    /**
     * Generates a monthly report and uploads it to Azure Blob Storage.
     *
     * <p>Remediation cr-java-0063 — replaces local file system write operations:
     * <ul>
     *   <li>Line 37 (source): {@code new File(REPORT_BASE_PATH)} →
     *       {@code blobServiceClient.getBlobContainerClient(reportsContainerName)}</li>
     *   <li>Line 39 (source): {@code reportDir.mkdirs()} →
     *       {@code containerClient.create()} (creates container if absent)</li>
     *   <li>Line 42 (source): {@code new FileWriter(fullPath)} →
     *       {@code blobClient.upload(dataStream, csvBytes.length, true)}</li>
     * </ul>
     * Report content is built in-memory and streamed directly to Azure Blob Storage,
     * eliminating any dependency on the local file system.</p>
     *
     * @param month the month for the report (e.g. "03")
     * @param year  the year for the report (e.g. "2024")
     * @return a map containing the operation status and blob URL
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String blobName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build Azure Blob Storage client.
            // Connection string is sourced from the AZURE_STORAGE_CONNECTION_STRING
            // environment variable — never hard-coded in source.
            // cr-java-0063 fix (line 37 source): replaces new File(REPORT_BASE_PATH)
            BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                    .connectionString(storageConnectionString)
                    .buildClient();

            // Obtain a reference to the reports container.
            // cr-java-0063 fix (line 39 source): replaces reportDir.mkdirs()
            // — creates the container if it does not already exist.
            BlobContainerClient containerClient = blobServiceClient
                    .getBlobContainerClient(reportsContainerName);
            if (!containerClient.exists()) {
                containerClient.create();
            }

            // Build CSV content in memory and upload to Azure Blob Storage.
            // cr-java-0063 fix (line 42 source): replaces new FileWriter(fullPath),
            // writer.write(...), and writer.close() with a single streaming upload.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            byte[] csvBytes = csvContent.getBytes(StandardCharsets.UTF_8);
            InputStream dataStream = new ByteArrayInputStream(csvBytes);

            BlobClient blobClient = containerClient.getBlobClient(blobName);
            blobClient.upload(dataStream, csvBytes.length, true);

            result.put("status", "generated");
            result.put("blobName", blobName);
            result.put("containerName", reportsContainerName);
            result.put("blobUrl", blobClient.getBlobUrl());
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Schedules a report generation task via Azure Service Bus scheduled message delivery.
     *
     * <p>Remediation cr-java-0111: Replaces server-local {@code java.util.Timer} scheduling
     * with Azure Service Bus scheduled messages. The task message is enqueued with a UTC-based
     * scheduled enqueue time, ensuring timezone-agnostic, distributed execution across all
     * cloud regions and container instances. The Azure Service Bus runtime delivers the message
     * at the specified UTC time regardless of the host server's local timezone setting.</p>
     *
     * @param month          the month for the report (e.g. "03")
     * @param year           the year for the report (e.g. "2024")
     * @param scheduledAtUtc the UTC instant at which the message should be delivered
     * @return a map containing the scheduling status and sequence number
     */
    public Map<String, Object> scheduleReportGeneration(String month, String year,
                                                         Instant scheduledAtUtc) {
        Map<String, Object> result = new HashMap<>();

        if (serviceBusConnectionString == null || serviceBusConnectionString.isEmpty()) {
            result.put("status", "skipped");
            result.put("reason", "Azure Service Bus connection string not configured; "
                    + "set AZURE_SERVICEBUS_CONNECTION_STRING environment variable.");
            return result;
        }

        // cr-java-0111 FIX: Use Azure Service Bus scheduled message delivery.
        // The message is enqueued with an explicit UTC-based OffsetDateTime, ensuring
        // timezone-agnostic scheduling across all cloud regions and container instances.
        // This replaces any server-local java.util.Timer or cron-based scheduling.
        try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(serviceBusQueueName)
                .buildClient()) {

            String payload = String.format(
                    "{\"action\":\"generateMonthlyReport\",\"month\":\"%s\",\"year\":\"%s\"}",
                    month, year);

            ServiceBusMessage message = new ServiceBusMessage(payload);
            message.setContentType("application/json");
            message.setMessageId("report-" + month + "-" + year + "-"
                    + Instant.now(utcClock).toEpochMilli());

            // Schedule the message at the specified UTC instant — timezone-agnostic delivery.
            OffsetDateTime scheduledEnqueueTime = scheduledAtUtc.atOffset(ZoneOffset.UTC);
            long sequenceNumber = senderClient.scheduleMessage(message, scheduledEnqueueTime);

            result.put("status", "scheduled");
            result.put("sequenceNumber", sequenceNumber);
            result.put("queueName", serviceBusQueueName);
            result.put("scheduledAtUtc", scheduledEnqueueTime.toString());

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the report download URL.
     *
     * <p>Remediation cr-java-0071: Hard-coded environment URL replaced with a value
     * injected from Azure App Configuration via the {@code app.reports.download-base-url}
     * property. When the base URL is configured, it is used directly; otherwise the
     * Azure Blob Storage URL is returned as the canonical download endpoint.</p>
     *
     * <p>Was (line 66 source):
     * {@code return "http://reports.resorts-internal.com:8080/download/" + reportName;}</p>
     *
     * <p>Uses HTTPS to comply with cloud security standards (cr-java-0088).
     * Replaces: {@code return "http://reports.resorts-internal.com:8080/download/" + reportName;}</p>
     *
     * @param reportName the name of the report blob
     * @return the HTTPS URL for downloading the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: Use externalized base URL from Azure App Configuration.
        // If app.reports.download-base-url is set (via APP_REPORTS_DOWNLOAD_BASE_URL env var
        // or Azure App Configuration key), use it; otherwise fall back to Azure Blob Storage URL.
        if (reportsDownloadBaseUrl != null && !reportsDownloadBaseUrl.isEmpty()) {
            return reportsDownloadBaseUrl + "/" + reportName;
        }
        // Fall back to Azure Blob Storage URL (already HTTPS — satisfies cr-java-0088).
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(storageConnectionString)
                .buildClient();
        BlobContainerClient containerClient = blobServiceClient
                .getBlobContainerClient(reportsContainerName);
        BlobClient blobClient = containerClient.getBlobClient(reportName);
        return blobClient.getBlobUrl();
    }

    /**
     * Returns system information including Azure Blob Storage configuration.
     *
     * <p>Replaces hard-coded local path references (cr-java-0062 / cr-java-0063):
     * was {@code info.put("reportPath", REPORT_BASE_PATH)} and
     * {@code info.put("backupPath", BACKUP_PATH)}.</p>
     *
     * <p>Remediation cr-java-0111: Server-local timezone timestamp generation replaced with
     * a UTC-based timestamp using {@code Instant.now(Clock.systemUTC())} formatted via
     * {@code DateTimeFormatter} with an explicit {@code ZoneOffset.UTC} offset. This ensures
     * consistent, unambiguous timestamps across all cloud regions and container instances,
     * regardless of the host JVM's default timezone setting.</p>
     *
     * <p>Was (line 70 source):
     * {@code String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());}</p>
     *
     * @return a map of system information
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX: Replace server-local timezone timestamp with UTC-based timestamp.
        // Previously: new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
        //   — inherits the server JVM's default timezone; inconsistent across cloud regions.
        // Now: Instant.now(Clock.systemUTC()) formatted with an explicit UTC offset
        //   — timezone-agnostic, consistent across all cloud regions and container instances.
        String timestamp = UTC_FORMATTER.format(Instant.now(utcClock));

        Map<String, Object> info = new HashMap<>();
        // Replaced hard-coded REPORT_BASE_PATH and BACKUP_PATH with Azure Blob container names
        info.put("reportsContainer", reportsContainerName);
        info.put("backupContainer", backupContainerName);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        info.put("timezone", "UTC");
        info.put("serviceBusQueue", serviceBusQueueName);
        return info;
    }
}
