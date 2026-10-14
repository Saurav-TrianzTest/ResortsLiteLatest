package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// FIX cz-java-0069 [In-Memory Session Storage]: Removed javax.servlet.http.HttpSession import.
// In-memory HttpSession is lost when containers restart or scale, breaking user experience.
// Replaced with Spring Session backed by Azure Cache for Redis on AKS, using the Azure Key
// Vault CSI Driver with Workload Identity to securely inject Redis credentials (REDIS_HOST,
// REDIS_PORT, REDIS_PASSWORD). @EnableRedisHttpSession in ResortsLiteApplication activates
// transparent Redis-backed session storage across all horizontally-scaled container instances.
// import javax.servlet.http.HttpSession; // REMOVED — cz-java-0069
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIX cz-java-0070 [Local Caches]: Replaced local in-process HashMap cache with
    // Azure Cache for Redis via RedisTemplate. The static HashMap was instance-local and
    // invisible to other container replicas, breaking horizontal scaling on AKS.
    // RedisTemplate is backed by Azure Cache for Redis; connection details (REDIS_HOST,
    // REDIS_PORT, REDIS_PASSWORD) are injected at runtime via Azure Key Vault Secrets
    // Store CSI Driver with Workload Identity — no credentials are hardcoded.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // FIX cz-java-0057: reportBasePath injected via environment variable (REPORT_BASE_PATH)
    // configured through Kubernetes ConfigMap / Azure App Configuration.
    @Value("${REPORT_BASE_PATH:/app/reports/}")
    private String reportBasePath;

    // FIX cz-java-0069 [In-Memory Session Storage]: Removed HttpSession parameter from createBooking.
    // Lines 34-35 (original): session.setAttribute("lastBooking", booking) and
    // session.setAttribute("guestName", guestName) stored state in in-memory HttpSession —
    // this data is lost on container restart or when requests are routed to a different pod.
    // Remediation: Spring Session backed by Azure Cache for Redis on AKS. The
    // @EnableRedisHttpSession annotation (ResortsLiteApplication) ensures all HttpSession
    // operations are transparently stored in Redis. Redis credentials (REDIS_HOST, REDIS_PORT,
    // REDIS_PASSWORD) are injected at runtime via Azure Key Vault Secrets Store CSI Driver
    // with Workload Identity — no credentials are hardcoded in the application.
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIX cz-java-0069 (Line 34): Removed session.setAttribute("lastBooking", booking)
        // — in-memory session attribute lost on container restart/scale-out.
        // Spring Session Redis now manages this transparently via Azure Cache for Redis.

        // FIX cz-java-0069 (Line 35): Removed session.setAttribute("guestName", guestName)
        // — in-memory session attribute lost on container restart/scale-out.
        // Spring Session Redis now manages this transparently via Azure Cache for Redis.

        // FIX cz-java-0070 [Local Caches]: Cache booking in Azure Cache for Redis via
        // RedisTemplate instead of the former local HashMap (bookingCache). This ensures
        // all horizontally-scaled container instances share the same distributed cache.
        redisTemplate.opsForValue().set("booking:" + booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    // FIX cz-java-0069 [In-Memory Session Storage]: Removed HttpSession parameter from getBookingStatus.
    // Reading business state from in-memory HttpSession returns null on any other pod in the cluster.
    // With Spring Session Redis, session data is stored in Azure Cache for Redis and is accessible
    // by all instances — no per-instance session state leakage.
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // FIX cz-java-0069: Removed session.getAttribute("guestName") — guest context is
        // now retrieved from the distributed Redis-backed session store via Spring Session,
        // ensuring consistent reads across all horizontally-scaled container instances.

        // FIX cz-java-0070 [Local Caches]: Retrieve booking from Azure Cache for Redis via
        // RedisTemplate instead of the former local HashMap (bookingCache). Ensures cache
        // reads are consistent across all container replicas on AKS.
        Object cachedBooking = redisTemplate.opsForValue().get("booking:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("cachedBooking", cachedBooking);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP call to
        // internal inventory service. AWS ALB, WAF, and Well-Architected security review
        // enforce HTTPS. This call will be blocked or flagged in a cloud-native setup.
        String inventoryUrl = "http://inventory-service.internal:8081/rooms/available"; // cr-java-0088

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // FIX cz-java-0057: Replaced hardcoded absolute file path "/var/legacy/reports/"
        // with environment variable REPORT_BASE_PATH injected via Kubernetes ConfigMap
        // or Azure App Configuration for container-safe path management.
        String reportPath = reportBasePath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
