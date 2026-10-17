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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Azure Blob Storage connection string injected from environment variable / application config
    @Value("${azure.storage.connection-string}")
    private String azureStorageConnectionString;

    // Azure Blob Storage container name for reports injected from environment variable / application config
    // Replaces hardcoded local path: /var/legacy/reports/ (cr-java-0063)
    @Value("${azure.storage.reports-container:reports}")
    private String reportsContainerName;

    // Azure Blob Storage container name for backups injected from environment variable / application config
    // Replaces hardcoded local path: C:\ResortBackups\nightly\ (cr-java-0063)
    @Value("${azure.storage.backup-container:resort-backups}")
    private String backupContainerName;

    // cr-java-0077 FIX: Hard-coded port 8080 replaced with value injected from Azure App Configuration
    // / environment variable SERVER_PORT. This enables dynamic port assignment by Azure container
    // orchestration platforms (Azure Container Apps, AKS) and avoids deployment-time port conflicts.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort; // cr-java-0077 resolved

    // cr-java-0071: Externalized hard-coded report download base URL to Azure App Configuration.
    // Previously: return "http://reports.resorts-internal.com:8080/download/" + reportName;
    // Now loaded from the 'app.reports.download.base-url' key in Azure App Configuration (or
    // application.properties as a fallback), enabling environment-agnostic deployments.
    @Value("${app.reports.download.base-url:http://reports.resorts-internal.com:8080/download/}")
    private String reportDownloadBaseUrl;

    // cr-java-0111 FIX: Azure Service Bus connection string for scheduled message delivery.
    // Replaces server-local java.util.Timer / TimerTask scheduling with Azure Service Bus
    // scheduled messages, enabling distributed, timezone-agnostic task execution across
    // all cloud instances without relying on server-local clock or timezone settings.
    @Value("${azure.servicebus.connection-string:${AZURE_SERVICEBUS_CONNECTION_STRING:}}")
    private String serviceBusConnectionString;

    // cr-java-0111 FIX: Azure Service Bus queue name for scheduled report tasks.
    // Injected from environment variable / application config to remain environment-agnostic.
    @Value("${azure.servicebus.report-queue:report-tasks}")
    private String reportTaskQueueName;

    /**
     * Creates and returns a BlobContainerClient for the given container name,
     * creating the container if it does not already exist.
     * Replaces java.io.File directory creation (reportDir.mkdirs()) with Azure Blob Storage
     * container provisioning — cr-java-0063.
     */
    private BlobContainerClient getOrCreateContainer(String containerName) {
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(azureStorageConnectionString)
                .buildClient();
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
        if (!containerClient.exists()) {
            containerClient.create();
        }
        return containerClient;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency (cr-java-0063)
            // Replaces: File reportDir = new File(REPORT_BASE_PATH) — line 37 in source
            // Replaces: reportDir.mkdirs() — line 39 in source
            // Replaces: FileWriter writer = new FileWriter(fullPath) — line 42 in source
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes(StandardCharsets.UTF_8);
            InputStream inputStream = new ByteArrayInputStream(contentBytes);

            // Upload report to Azure Blob Storage instead of writing to local file system (cr-java-0063)
            BlobContainerClient containerClient = getOrCreateContainer(reportsContainerName);
            BlobClient blobClient = containerClient.getBlobClient(fileName);
            blobClient.upload(inputStream, contentBytes.length, true);

            String blobUrl = blobClient.getBlobUrl();

            result.put("status", "generated");
            result.put("path", blobUrl);
            result.put("serverPort", serverPort); // cr-java-0077 resolved

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
        // Azure App Configuration via @Value("${app.reports.download.base-url}").
        // The base URL is now resolved at runtime from external configuration, making the
        // application portable across dev, staging, and production environments without
        // requiring code changes.
        return reportDownloadBaseUrl + reportName; // cr-java-0071 resolved
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // cr-java-0111 FIX: Replaced server-local SimpleDateFormat / new Date() with
        // java.time.OffsetDateTime.now(ZoneOffset.UTC) formatted via DateTimeFormatter.
        // This produces a timezone-agnostic ISO-8601 UTC timestamp that is consistent
        // across all cloud instances regardless of the host server's local timezone
        // setting, eliminating time-related logic errors in distributed deployments.
        // Previously: new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
        String timestamp = OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); // cr-java-0111 resolved

        Map<String, Object> info = new HashMap<>();
        info.put("reportsContainer", reportsContainerName);   // Azure Blob Storage container (replaces REPORT_BASE_PATH — cr-java-0063)
        info.put("backupContainer", backupContainerName);     // Azure Blob Storage container (replaces BACKUP_PATH — cr-java-0063)
        info.put("serverPort", serverPort);                   // cr-java-0077 resolved
        info.put("generatedAt", timestamp);                   // cr-java-0111 resolved — UTC timestamp
        return info;
    }

    /**
     * cr-java-0111 FIX: Schedules a report generation task via Azure Service Bus scheduled
     * message delivery instead of using java.util.Timer or server-local cron scheduling.
     *
     * <p>Azure Service Bus scheduled messages are enqueued with a future
     * {@link OffsetDateTime} expressed in UTC, ensuring timezone-agnostic delivery
     * across all cloud regions and container instances. The receiving consumer
     * (another instance of this service or a dedicated worker) processes the message
     * at the scheduled time without any dependency on the originating server's local
     * clock or timezone configuration.
     *
     * <p>Replaces the pattern:
     * <pre>
     *   Timer timer = new Timer();
     *   timer.schedule(new TimerTask() { ... }, delay);
     * </pre>
     *
     * @param month           target report month (e.g. "03")
     * @param year            target report year  (e.g. "2024")
     * @param scheduledAtUtc  UTC date-time at which the message should be delivered
     */
    public void scheduleReportGeneration(String month, String year, OffsetDateTime scheduledAtUtc) {
        // cr-java-0111: Build the Azure Service Bus sender for the report-tasks queue.
        // The connection string is injected from environment variable
        // AZURE_SERVICEBUS_CONNECTION_STRING, keeping credentials out of source code.
        try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(reportTaskQueueName)
                .buildClient()) {

            // Compose the scheduled message payload with report parameters.
            String messageBody = String.format(
                    "{\"action\":\"generateMonthlyReport\",\"month\":\"%s\",\"year\":\"%s\"}", month, year);
            ServiceBusMessage message = new ServiceBusMessage(messageBody);

            // Schedule the message for future delivery at the specified UTC time.
            // Azure Service Bus handles the timezone-agnostic delivery guarantee,
            // replacing any server-local Timer / TimerTask scheduling. (cr-java-0111)
            senderClient.scheduleMessage(message, scheduledAtUtc);
        }
    }
}
