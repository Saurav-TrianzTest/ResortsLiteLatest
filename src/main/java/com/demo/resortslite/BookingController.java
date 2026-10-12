package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0069 FIX: HttpSession is now backed by Spring Session + Amazon ElastiCache (Redis).
// The javax.servlet.http.HttpSession interface is retained but session data is stored in Redis,
// enabling stateless, horizontally-scalable containers on EKS. No instance-local state is kept.
// Spring Session transparently intercepts all HttpSession operations and delegates to Redis,
// so session data survives container restarts and is shared across all EKS pod replicas.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    // cz-java-0057 FIX: Replaced hardcoded absolute file path with environment variable
    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String reportBasePath;

    @Autowired
    private BookingService bookingService;

    // cz-java-0070 FIX: Replaced local in-memory HashMap cache (instance-local, invisible
    // to other EKS pod replicas) with Amazon ElastiCache (Redis) via Spring Data RedisTemplate.
    // REDIS_HOST and REDIS_PORT are injected via Kubernetes ConfigMap / environment variables.
    // CACHE_TTL_SECONDS controls entry expiry (default 3600s) to prevent stale data accumulation.
    // All EKS pod replicas share the same Redis-backed cache, enabling horizontal scaling.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${CACHE_TTL_SECONDS:3600}")
    private long cacheTtlSeconds;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // cz-java-0069 FIX: HttpSession is transparently backed by Spring Session Redis
            // (via @EnableRedisHttpSession in SessionConfig). Session data is stored in
            // Amazon ElastiCache, shared across all EKS pod replicas — no in-memory state.
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 FIX (Line 34): session.setAttribute("lastBooking", ...) — previously stored
        // in-memory HttpSession (lost on container restart/scale). Now persisted to Amazon
        // ElastiCache (Redis) via Spring Session. Any EKS pod can read this session data.
        session.setAttribute("lastBooking", booking);
        // cz-java-0069 FIX (Line 35): session.setAttribute("guestName", ...) — previously stored
        // in-memory HttpSession (lost on container restart/scale). Now persisted to Amazon
        // ElastiCache (Redis) via Spring Session, enabling horizontal scaling and failover.
        session.setAttribute("guestName", guestName);

        // cz-java-0070 FIX: Store booking in Amazon ElastiCache (Redis) instead of local HashMap.
        // Key is prefixed with "booking:" for namespace isolation in the shared Redis cluster.
        // TTL is set via CACHE_TTL_SECONDS env var (default 3600s) to auto-expire stale entries.
        String cacheKey = "booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, cacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // cz-java-0069 FIX: HttpSession is transparently backed by Spring Session Redis.
            // Session data is retrieved from Amazon ElastiCache, consistent across all EKS pods.
            HttpSession session) {

        // cz-java-0069 FIX: Session attribute is now read from Amazon ElastiCache (Redis)
        // via Spring Session — returns correct value regardless of which EKS pod handles the request.
        String lastGuest = (String) session.getAttribute("guestName");

        // cz-java-0070 FIX: Retrieve booking from Amazon ElastiCache (Redis) instead of local HashMap.
        // Returns the cached booking if present; falls back to the database lookup via bookingService.
        String cacheKey = "booking:" + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
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
        // cz-java-0057 FIX: Replaced hardcoded absolute path /var/legacy/reports/ with
        // environment variable REPORT_BASE_PATH injected via Kubernetes ConfigMap
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
