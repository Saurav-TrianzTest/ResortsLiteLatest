package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// cr-java-0065: HttpSession import removed — session state is now stored in GCP Memorystore
// for Redis via RedisTemplate, enabling stateless horizontal scaling across all instances.

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0065: RedisTemplate replaces HttpSession for distributed session state storage.
    // All session attributes are persisted in GCP Memorystore for Redis so that any instance
    // in the cluster can read them, eliminating server affinity and supporting auto-scaling.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cr-java-0067 FIX: Removed static in-memory HashMap bookingCache (was unbounded, instance-local,
    // and had no TTL). Booking cache entries are now stored in GCP Memorystore for Redis via
    // RedisTemplate with a configurable TTL (BOOKING_CACHE_TTL_SECONDS), ensuring consistent
    // cache state across all instances and preventing memory exhaustion.

    // Session TTL: 30 minutes — aligns with spring.session.timeout in application.properties
    private static final long SESSION_TTL_SECONDS = 1800L;

    // cr-java-0067: Booking cache TTL — 60 minutes default. Entries expire automatically,
    // preventing stale data and unbounded memory growth in GCP Memorystore for Redis.
    private static final long BOOKING_CACHE_TTL_SECONDS = 3600L;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX (Lines 34-35): Session attributes previously stored in HttpSession
        // are now written to GCP Memorystore for Redis via RedisTemplate.  Each key is
        // namespaced by sessionId so multiple concurrent users remain isolated.  A TTL of
        // SESSION_TTL_SECONDS is applied to prevent stale data accumulation.
        if (sessionId != null && !sessionId.isEmpty()) {
            String lastBookingKey = "session:" + sessionId + ":lastBooking";
            String guestNameKey   = "session:" + sessionId + ":guestName";
            redisTemplate.opsForValue().set(lastBookingKey, booking,       SESSION_TTL_SECONDS, TimeUnit.SECONDS);
            redisTemplate.opsForValue().set(guestNameKey,   guestName,     SESSION_TTL_SECONDS, TimeUnit.SECONDS);
        }

        // cr-java-0067 FIX: Booking is now cached in GCP Memorystore for Redis with a TTL of
        // BOOKING_CACHE_TTL_SECONDS (3600 s). This replaces the former static HashMap which
        // was instance-local, had no expiration, and caused stale data across scaled instances.
        String bookingId = (String) booking.get("bookingId");
        if (bookingId != null) {
            String bookingCacheKey = "cache:booking:" + bookingId;
            redisTemplate.opsForValue().set(bookingCacheKey, booking, BOOKING_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        // cr-java-0065 FIX (Line 48): Session attribute previously read from HttpSession is
        // now retrieved from GCP Memorystore for Redis via RedisTemplate.  Because Redis is
        // a shared, external store, the value is visible to every instance in the cluster —
        // eliminating the data-loss that occurred when ALB/Cloud Load Balancer routed the
        // request to a different instance than the one that created the session.
        String lastGuest = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            String guestNameKey = "session:" + sessionId + ":guestName";
            lastGuest = (String) redisTemplate.opsForValue().get(guestNameKey);
        }

        // cr-java-0067 FIX: Booking details are now read from the Redis-backed cache
        // (GCP Memorystore) instead of the former instance-local static HashMap.
        // Cache miss falls back to bookingService.getBookingById() transparently.
        String bookingCacheKey = "cache:booking:" + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(bookingCacheKey);

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
        // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute
        // file path. This path does not exist inside a container image. Container images
        // have their own isolated file systems — /var/legacy/reports won't be present.
        String reportPath = "/var/legacy/reports/" + month + "_bookings.pdf"; // czr-java-001

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
