package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0069 (Line 6): Replaced in-memory HttpSession with Spring Session-managed
// HttpSession backed by Azure Cache for Redis. Spring Session transparently intercepts
// all HttpSession operations and stores/retrieves session data from Redis, enabling
// stateless horizontal scaling across multiple AKS pod replicas without sticky sessions.
// Session data survives container restarts and pod scaling events because it is stored
// externally in Azure Cache for Redis, not in the JVM heap of any individual container.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    @Value("${REPORT_BASE_PATH:/var/reports/}")
    private String reportBasePath;

    // cz-java-0070 (Line 19): Replaced local in-process HashMap cache with Azure Cache for Redis
    // via RedisTemplate. The local static HashMap was instance-local and invisible to other AKS
    // pod replicas, breaking horizontal scaling. RedisTemplate stores booking cache entries in
    // the shared Azure Cache for Redis cluster, making cached data available to all pod replicas.
    // Redis connection credentials (SPRING_REDIS_HOST, SPRING_REDIS_PORT, SPRING_REDIS_PASSWORD,
    // SPRING_REDIS_SSL) are injected from Azure Key Vault via the Secrets Store CSI Driver on AKS.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cz-java-0070: Cache TTL in seconds — controls how long booking entries live in Redis.
    // Externalised via environment variable BOOKING_CACHE_TTL_SECONDS (default: 3600s / 1 hour).
    @Value("${BOOKING_CACHE_TTL_SECONDS:3600}")
    private long bookingCacheTtlSeconds;

    // cz-java-0069 (Line 30): HttpSession parameter is now backed by Spring Session +
    // Azure Cache for Redis (configured via RedisHttpSessionConfig). Session data is stored
    // externally in Redis, so any AKS pod replica can read/write the same session —
    // no sticky sessions required. Container restarts do NOT lose session state.
    // Redis credentials are injected from Azure Key Vault via Secrets Store CSI Driver:
    //   SPRING_REDIS_HOST, SPRING_REDIS_PORT, SPRING_REDIS_PASSWORD, SPRING_REDIS_SSL
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 (Line 34): session.setAttribute now persists to Azure Cache for Redis
        // via Spring Session. All AKS pod replicas share the same Redis-backed session store.
        // Session data is NOT stored in JVM heap — it survives container restarts and scaling.
        session.setAttribute("lastBooking", booking); // cz-java-0069: externalized to Redis
        // cz-java-0069 (Line 35): session.setAttribute now persists to Azure Cache for Redis
        // via Spring Session. Guest name is stored in the distributed Redis session store,
        // not in local JVM memory — consistent across all pod replicas in the AKS cluster.
        session.setAttribute("guestName", guestName); // cz-java-0069: externalized to Redis

        // cz-java-0070: Store booking in Azure Cache for Redis instead of local HashMap.
        // Key is prefixed with "booking:" namespace to avoid collisions with session keys.
        // TTL is set to BOOKING_CACHE_TTL_SECONDS so stale entries are automatically evicted.
        String cacheKey = "booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    // cz-java-0069 (Line 55): HttpSession parameter is now backed by Spring Session +
    // Azure Cache for Redis. Reading session attributes retrieves data from the shared
    // Redis store, ensuring consistent session state regardless of which AKS pod replica
    // handles the request. No session data is lost on container restart or scale-out.
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cz-java-0069: Session attribute is read from Azure Cache for Redis via Spring Session —
        // consistent across all pod replicas in the AKS cluster, not from local JVM memory.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
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
        // cz-java-0057: Replaced hardcoded absolute path with environment variable REPORT_BASE_PATH
        // injected via Kubernetes ConfigMap / Azure App Configuration for container portability.
        String reportPath = reportBasePath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
