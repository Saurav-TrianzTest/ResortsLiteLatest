package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0069: Replaced in-memory HttpSession with Spring Session backed by
    // Amazon ElastiCache (Redis) via Spring Session Data Redis. Session data is
    // shared across all EKS pod instances — survives container restarts and scaling.
    @Autowired
    private SessionRepository sessionRepository;

    // cz-java-0070 FIX: Replaced local in-memory HashMap cache with Amazon ElastiCache (Redis)
    // via RedisTemplate. The local static Map was instance-local and invisible to other pods
    // in a horizontally scaled EKS deployment. Redis provides a shared, distributed cache
    // accessible by all container instances. Connection details are injected via environment
    // variables (REDIS_HOST, REDIS_PORT) mapped from Kubernetes ConfigMaps/Secrets with IRSA.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // TTL for cache entries in Redis (seconds) — injected via environment variable / ConfigMap
    @Value("${BOOKING_CACHE_TTL_SECONDS:3600}")
    private long bookingCacheTtlSeconds;

    // Injected from Kubernetes ConfigMap / environment variable — replaces hardcoded absolute path
    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String reportBasePath;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 FIX: Session state stored in Amazon ElastiCache (Redis) via Spring Session.
        // Replaces in-memory HttpSession.setAttribute() calls (original lines 34-35).
        // Supports horizontal scaling on EKS — session data is shared across all pod instances.
        Session session = (sessionId != null) ? sessionRepository.findById(sessionId) : null;
        if (session == null) {
            session = sessionRepository.createSession();
        }
        session.setAttribute("lastBooking", booking); // cz-java-0069: now stored in Redis/ElastiCache
        session.setAttribute("guestName", guestName); // cz-java-0069: now stored in Redis/ElastiCache
        sessionRepository.save(session);

        // cz-java-0070 FIX: Store booking in Amazon ElastiCache (Redis) instead of local HashMap.
        // All EKS pod instances share this distributed cache — no stale or missing data on scale-out.
        String cacheKey = "booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        response.put("sessionId", session.getId());
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false) String sessionId) {

        // cz-java-0069 FIX: Reading business state from Amazon ElastiCache (Redis) via Spring Session.
        // Session data is available across all pod instances in the EKS cluster.
        String lastGuest = null;
        if (sessionId != null) {
            Session session = sessionRepository.findById(sessionId);
            if (session != null) {
                lastGuest = (String) session.getAttribute("guestName");
            }
        }

        // cz-java-0070 FIX: Read booking from Amazon ElastiCache (Redis) distributed cache.
        // Replaces local HashMap lookup — cache is now shared across all EKS pod instances.
        String cacheKey = "booking:" + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
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
        // Path injected via REPORT_BASE_PATH environment variable / Kubernetes ConfigMap (cz-java-0057)
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
