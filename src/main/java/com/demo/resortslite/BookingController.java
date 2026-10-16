package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native, stateless REST controller.
 *
 * <p>cr-java-0065 FIX: All HTTP session state (lastBooking, guestName) has been
 * removed and replaced with Azure Cache for Redis via {@link RedisTemplate}.
 * Session attributes are now stored in Redis with a configurable TTL so that
 * every application instance in the cluster shares the same state, enabling
 * horizontal scaling, zero-downtime deployments, and safe auto-scaling.</p>
 *
 * <p>Was: {@code HttpSession.setAttribute("lastBooking", booking)} /
 *         {@code HttpSession.getAttribute("guestName")}</p>
 * <p>Now: {@code redisTemplate.opsForValue().set(key, value, ttl, TimeUnit.MINUTES)}</p>
 *
 * <p>cr-java-0067 FIX: In-memory cache ({@code HashMap bookingCache}) without TTL has been
 * removed and replaced with Azure Cache for Redis with a configurable TTL policy.
 * The static {@code bookingCache} field caused indefinite memory growth, potential
 * out-of-memory errors, and stale data inconsistencies across multiple instances.
 * All cache reads and writes now go through {@link RedisTemplate} with an explicit
 * TTL, ensuring distributed cache consistency and bounded memory usage.</p>
 *
 * <p>Was: {@code private static final Map<String, Object> bookingCache = new HashMap<>();}
 *         {@code bookingCache.put(bookingId, booking);}</p>
 * <p>Now: {@code redisTemplate.opsForValue().set("cache:booking:" + bookingId, booking, cacheTtlMinutes, TimeUnit.MINUTES)}</p>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0065 FIX: RedisTemplate replaces HttpSession for distributed session state.
     * cr-java-0067 FIX: RedisTemplate also replaces the in-memory bookingCache HashMap,
     * providing TTL-bounded, distributed caching via Azure Cache for Redis.
     * Backed by Azure Cache for Redis; configured via spring.redis.* properties.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * TTL (in minutes) for Redis session keys.
     * Configurable via the {@code SESSION_TTL_MINUTES} environment variable.
     * Defaults to 30 minutes if not set.
     */
    @Value("${app.session.ttl-minutes:${SESSION_TTL_MINUTES:30}}")
    private long sessionTtlMinutes;

    /**
     * cr-java-0067 FIX: TTL (in minutes) for Redis booking cache entries.
     * Configurable via the {@code CACHE_TTL_MINUTES} environment variable.
     * Defaults to 60 minutes if not set.
     * Replaces the unbounded in-memory {@code bookingCache} HashMap that had no expiration.
     */
    @Value("${app.cache.ttl-minutes:${CACHE_TTL_MINUTES:60}}")
    private long cacheTtlMinutes;

    /**
     * Remediation cr-java-0071: Hard-coded inventory service URL replaced with a value
     * injected from Azure App Configuration via the {@code app.inventory.url} property.
     * Set the {@code APP_INVENTORY_URL} environment variable (or configure the key in
     * Azure App Configuration) to supply the correct endpoint per deployment environment.
     *
     * <p>Was: {@code String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";}</p>
     */
    @Value("${app.inventory.url:${app.inventory.endpoint:http://inventory-service.internal:8081}}/rooms/available")
    private String inventoryUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session state is now stored in Azure Cache for Redis instead of
        // the in-process HttpSession. Each key is namespaced by bookingId so that all
        // application instances share the same view of the data. A TTL is applied to
        // prevent unbounded memory growth in the Redis cache.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set("session:lastBooking:" + bookingId, booking, sessionTtlMinutes, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set("session:guestName:" + bookingId, guestName, sessionTtlMinutes, TimeUnit.MINUTES);

        // cr-java-0067 FIX: Booking is now cached in Azure Cache for Redis with an explicit
        // TTL (cacheTtlMinutes) instead of the former unbounded in-memory HashMap.
        // This prevents indefinite memory growth, eliminates stale data across instances,
        // and ensures all application replicas share a consistent, TTL-bounded cache.
        redisTemplate.opsForValue().set("cache:booking:" + bookingId, booking, cacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // cr-java-0065 FIX: Guest name is now retrieved from Azure Cache for Redis instead
        // of the in-process HttpSession. This ensures consistent results regardless of
        // which application instance handles the request in a horizontally scaled cluster.
        String lastGuest = (String) redisTemplate.opsForValue().get("session:guestName:" + bookingId);

        // cr-java-0067 FIX: Booking details are now retrieved from Azure Cache for Redis
        // (TTL-bounded distributed cache) instead of the former in-memory HashMap.
        // Falls back to the BookingService (database) if the cache entry has expired.
        Object cachedBooking = redisTemplate.opsForValue().get("cache:booking:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: Hard-coded URL replaced with externalized configuration.
        // inventoryUrl is injected via @Value from the app.inventory.url property,
        // which is backed by the APP_INVENTORY_URL environment variable and can be
        // managed centrally in Azure App Configuration for each deployment environment.
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
