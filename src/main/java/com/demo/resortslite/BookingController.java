package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * BookingController – cloud-native session management via Spring Session + Amazon ElastiCache for Redis.
 *
 * cr-java-0065 FIX: All HTTP session state is now stored in Amazon ElastiCache for Redis through
 * Spring Session. The {@code @EnableRedisHttpSession} annotation on this class (or on a dedicated
 * {@code HttpSessionConfig} class) transparently replaces the in-process {@link HttpSession}
 * implementation with a Redis-backed one. Every call to {@code session.setAttribute()} /
 * {@code session.getAttribute()} is serialised to the shared Redis cluster, so any EC2 instance
 * in the Auto Scaling Group can serve subsequent requests for the same session without sticky
 * sessions on the ALB. This satisfies the stateless-instance requirement of 12-factor apps and
 * allows safe horizontal scaling and instance replacement.
 *
 * cr-java-0067 FIX: The unbounded static in-memory {@code HashMap} used as a booking cache has
 * been replaced with Amazon ElastiCache for Redis via {@link RedisTemplate}. Each cache entry is
 * stored under the key prefix {@code booking:cache:} with a configurable TTL (default 30 minutes,
 * controlled by the {@code BOOKING_CACHE_TTL_SECONDS} environment variable). This eliminates:
 *   - Indefinite memory growth and potential OOM errors on long-running instances.
 *   - Stale data inconsistencies across multiple EC2 instances (cache is now shared).
 *   - Instance-local state that breaks horizontal scaling and Auto Scaling Group replacements.
 *
 * Required infrastructure:
 *   - Amazon ElastiCache for Redis cluster (single-node or cluster-mode)
 *   - Environment variables REDIS_HOST and REDIS_PORT (or AWS SSM parameters) injected at
 *     deployment time so no Redis coordinates are hard-coded in the application.
 */
@RestController
@RequestMapping("/api/bookings")
@EnableRedisHttpSession
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0067 FIX: RedisTemplate replaces the static in-memory HashMap bookingCache.
     * Booking objects are stored in Amazon ElastiCache for Redis with a TTL so that:
     *   - Cache entries expire automatically (no unbounded memory growth).
     *   - All application instances share the same cache (no stale-data divergence).
     *   - The cache survives instance replacement and Auto Scaling Group scale-in events.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * TTL for booking cache entries in seconds.
     * Defaults to 1800 s (30 minutes); override via the BOOKING_CACHE_TTL_SECONDS
     * environment variable (e.g. set in ECS task definition / EKS pod spec / Elastic
     * Beanstalk environment properties, or sourced from AWS SSM Parameter Store).
     */
    @Value("${booking.cache.ttl.seconds:1800}")
    private long bookingCacheTtlSeconds;

    /** Redis key prefix for booking cache entries. */
    private static final String BOOKING_CACHE_PREFIX = "booking:cache:";

    /**
     * Inventory service URL – externalised from AWS Systems Manager Parameter Store.
     * Resolved via the {@code app.inventory.service.url} property, which is backed by
     * the {@code APP_INVENTORY_SERVICE_URL} environment variable (set by SSM Parameter
     * Store integration at deployment time).
     * Replaces the hard-coded URL {@code http://inventory-service.internal:8081/rooms/available}
     * (cr-java-0071).
     */
    @Value("${app.inventory.service.url}")
    private String inventoryServiceUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session attributes are now stored in Amazon ElastiCache for Redis
        // via Spring Session. The HttpSession interface is unchanged; Spring Session transparently
        // serialises these attributes to the shared Redis cluster so that every application
        // instance in the Auto Scaling Group can read them, eliminating server affinity and
        // enabling safe horizontal scaling and instance replacement.
        session.setAttribute("lastBooking", booking); // now Redis-backed via Spring Session
        session.setAttribute("guestName", guestName); // now Redis-backed via Spring Session

        // cr-java-0067 FIX: Store booking in Amazon ElastiCache for Redis with TTL.
        // Replaces the former static HashMap (bookingCache) which had no expiration policy,
        // grew without bound, and was invisible to other EC2 instances in the cluster.
        // The entry expires automatically after bookingCacheTtlSeconds (default 30 min),
        // preventing stale data and unbounded memory growth.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, Duration.ofSeconds(bookingCacheTtlSeconds));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: Session attribute is now read from Amazon ElastiCache for Redis
        // via Spring Session. Any instance in the cluster can serve this request and will
        // retrieve the correct guestName regardless of which instance handled the original
        // createBooking request, eliminating the data-loss risk under load balancing.
        String lastGuest = (String) session.getAttribute("guestName"); // now Redis-backed via Spring Session

        // cr-java-0067 FIX: Booking lookup now reads from Amazon ElastiCache for Redis
        // instead of the former instance-local static HashMap. Returns null (cache miss)
        // if the entry has expired per the configured TTL, which is the expected behaviour
        // for a distributed cache with expiration policies.
        String cacheKey = BOOKING_CACHE_PREFIX + bookingId;
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
        // cr-java-0071 FIX: Hard-coded environment URL replaced with value retrieved from
        // AWS Systems Manager Parameter Store via the app.inventory.service.url property.
        // The URL is injected at startup from the SSM parameter /resortslite/inventory/service/url,
        // enabling environment-agnostic deployments without code changes per environment.
        String inventoryUrl = inventoryServiceUrl;

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
