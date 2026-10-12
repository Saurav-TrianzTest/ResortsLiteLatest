package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0069: HttpSession is now transparently backed by Amazon ElastiCache (Redis)
// via Spring Session (spring-session-data-redis). No API change required — Spring Session
// intercepts HttpSession calls and delegates to Redis, enabling stateless horizontal
// scaling across multiple EKS pods without sticky sessions.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0070: RedisTemplate backed by Amazon ElastiCache (Redis) on EKS.
    // Replaces the local in-memory HashMap cache so all pod instances share the same
    // distributed cache — connection details injected via Kubernetes ConfigMaps/Secrets.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${REPORT_BASE_PATH:/var/legacy/reports/}")
    private String reportBasePath;

    // cz-java-0070: Booking cache TTL (seconds) — sourced from environment variable.
    // Defaults to 3600 s (1 hour). Set BOOKING_CACHE_TTL_SECONDS in the EKS ConfigMap.
    @Value("${BOOKING_CACHE_TTL_SECONDS:3600}")
    private long bookingCacheTtlSeconds;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            // cz-java-0069: HttpSession parameter is transparently backed by Amazon ElastiCache (Redis)
            // via Spring Session (@EnableRedisHttpSession in RedisSessionConfig). All EKS pod instances
            // share the same Redis session store, eliminating sticky-session requirements and supporting
            // horizontal auto-scaling. Session data is no longer stored in-memory on the local JVM heap.
            @RequestParam String checkOut,
            HttpSession session) { // cz-java-0069: Redis-backed via Spring Session — not in-memory

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069: Session attributes are stored in Amazon ElastiCache (Redis) via
        // Spring Session. All EKS pod instances share the same Redis session store,
        // eliminating sticky-session requirements and supporting horizontal auto-scaling.
        session.setAttribute("lastBooking", booking); // cz-java-0069: Redis-backed via Spring Session (ElastiCache)
        session.setAttribute("guestName", guestName); // cz-java-0069: Redis-backed via Spring Session (ElastiCache)

        // cz-java-0070: Store booking in Amazon ElastiCache (Redis) via RedisTemplate.
        // All EKS pod instances share this distributed cache; TTL prevents stale entries.
        String cacheKey = "booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) { // cz-java-0069: session is now Redis-backed via Spring Session

        // cz-java-0069: Session attribute is retrieved from Amazon ElastiCache (Redis) via
        // Spring Session — consistent across all EKS pod instances in the cluster.
        String lastGuest = (String) session.getAttribute("guestName"); // cz-java-0069: Redis-backed via Spring Session

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
        // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute
        // file path. This path does not exist inside a container image. Container images
        // have their own isolated file systems — /var/legacy/reports won't be present.
        String reportPath = reportBasePath + month + "_bookings.pdf"; // cz-java-0057: replaced hardcoded path with env var REPORT_BASE_PATH

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
