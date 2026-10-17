package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// cr-java-0065 FIX: Removed HttpSession import. Session state is now externalised to
// Azure Cache for Redis via RedisTemplate, enabling stateless horizontal scaling.
// All session.setAttribute / session.getAttribute calls have been replaced with
// redisTemplate.opsForValue().set / get calls backed by the shared Redis store.

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0065 FIX: RedisTemplate replaces HttpSession for distributed session state.
    // Azure Cache for Redis is injected here; all instances share the same Redis store,
    // eliminating server affinity and enabling safe auto-scaling and failover.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cr-java-0067 FIX: Removed static in-memory bookingCache (HashMap) which had no TTL,
    // caused indefinite memory growth, and was invisible to other application instances.
    // Booking entries are now stored in Azure Cache for Redis with a 60-minute TTL via
    // redisTemplate.opsForValue().set(..., BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES).
    // This enables distributed cache sharing across all instances, prevents memory
    // exhaustion, and ensures cache consistency in horizontally-scaled cloud deployments.

    // cr-java-0071: Externalized hard-coded inventory service URL to Azure App Configuration.
    // Previously: String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";
    // Now loaded from the 'app.inventory.service.url' key in Azure App Configuration (or
    // application.properties as a fallback), enabling environment-agnostic deployments.
    @Value("${app.inventory.service.url:http://inventory-service.internal:8081/rooms/available}")
    private String inventoryServiceUrl;

    // TTL for Redis session keys — 30 minutes, matching typical HTTP session timeout.
    private static final long SESSION_TTL_MINUTES = 30L;

    // cr-java-0067 FIX: TTL for Redis booking cache entries — 60 minutes.
    // Replaces the unbounded static HashMap that had no expiration policy.
    // Entries automatically expire in Redis, preventing stale data and memory exhaustion.
    private static final long BOOKING_CACHE_TTL_MINUTES = 60L;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Booking state is now stored in Azure Cache for Redis instead of
        // the in-memory HTTP session. Keys are namespaced by sessionId so that any application
        // instance can retrieve the data, supporting horizontal scaling and failover without
        // server affinity. A 30-minute TTL mirrors the previous session timeout behaviour.
        String lastBookingKey = "session:" + sessionId + ":lastBooking";
        String guestNameKey   = "session:" + sessionId + ":guestName";
        redisTemplate.opsForValue().set(lastBookingKey, booking, SESSION_TTL_MINUTES, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(guestNameKey, guestName, SESSION_TTL_MINUTES, TimeUnit.MINUTES);

        // cr-java-0067 FIX: Replaced bookingCache.put() on the static in-memory HashMap with
        // a Redis set call that includes a 60-minute TTL. The booking entry is now stored in
        // Azure Cache for Redis under a namespaced key, making it visible to all application
        // instances and automatically expiring stale entries to prevent memory exhaustion.
        String bookingCacheKey = "booking:cache:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(bookingCacheKey, booking, BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        // cr-java-0065 FIX: Guest name is now retrieved from Azure Cache for Redis instead of
        // the in-memory HTTP session. Any instance in the cluster can serve this request
        // because all instances share the same Redis store — no server affinity required.
        String guestNameKey = "session:" + sessionId + ":guestName";
        String lastGuest = (String) redisTemplate.opsForValue().get(guestNameKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: Hard-coded environment URL replaced with value injected from
        // Azure App Configuration via @Value("${app.inventory.service.url}").
        // The URL is now resolved at runtime from external configuration, making the
        // application portable across dev, staging, and production environments without
        // requiring code changes.
        String inventoryUrl = inventoryServiceUrl; // cr-java-0071 resolved

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
