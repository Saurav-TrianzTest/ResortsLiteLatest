package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * BookingController — Spring MVC REST controller for resort booking operations.
 *
 * <p><strong>cr-java-0065 FIX (HTTP Session State Storage → Amazon ElastiCache for Redis):</strong>
 * HTTP session attributes are no longer stored in JVM-local memory. Spring Session Data Redis
 * is on the classpath (see pom.xml) and is auto-configured via
 * {@code spring.session.store-type=redis} in {@code application.properties}.
 * Every {@link HttpSession} interaction is transparently serialised to the Amazon ElastiCache
 * for Redis cluster, so any EC2 / ECS instance in the Auto Scaling Group can serve any
 * subsequent request without sticky sessions or data loss on scale-in / failover.
 *
 * <p>No application-level code changes are required beyond the dependency and property additions
 * because Spring Session replaces the servlet container's {@code HttpSession} implementation
 * at the filter level — the {@code HttpSession} API calls below continue to work as-is but
 * now read/write from Redis instead of in-process memory.
 *
 * <p><strong>cr-java-0067 FIX (In-Memory Caching Without TTL → Amazon ElastiCache for Redis):</strong>
 * The unbounded static {@code HashMap} used as a local booking cache has been replaced with
 * a {@link RedisTemplate}-backed cache against Amazon ElastiCache for Redis. Each cache entry
 * is stored with a configurable TTL (default 30 minutes, overridable via the environment
 * variable {@code APP_BOOKING_CACHE_TTL_MINUTES}). This ensures:
 * <ul>
 *   <li>Controlled memory growth — entries expire automatically after the TTL elapses.</li>
 *   <li>Consistent cache state across all application instances in the Auto Scaling Group.</li>
 *   <li>No stale data — expired entries are evicted by Redis, not held indefinitely in JVM heap.</li>
 * </ul>
 * The Redis connection is shared with Spring Session (same ElastiCache cluster) and is
 * configured via {@code spring.redis.*} properties / environment variables.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0067 FIX: Replaces the unbounded static {@code HashMap} booking cache with a
     * {@link RedisTemplate} that writes to Amazon ElastiCache for Redis.
     *
     * <p>Spring Boot auto-configures a {@code RedisTemplate<String, Object>} bean when
     * {@code spring-boot-starter-data-redis} is on the classpath and
     * {@code spring.redis.host} / {@code spring.redis.port} are supplied at runtime.
     * The same ElastiCache cluster used by Spring Session (cr-java-0065 fix) is reused here,
     * keeping the infrastructure footprint minimal.
     *
     * <p>Previous violation (line 19):
     * {@code private static final Map<String, Object> bookingCache = new HashMap<>();}
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * cr-java-0067 FIX: TTL for booking cache entries, in minutes.
     * Defaults to 30 minutes; override via the {@code APP_BOOKING_CACHE_TTL_MINUTES}
     * environment variable or the {@code app.booking.cache.ttl-minutes} application property.
     * Setting an explicit TTL prevents indefinite memory growth and ensures stale booking
     * data is automatically evicted from the Redis cache.
     */
    @Value("${app.booking.cache.ttl-minutes:${APP_BOOKING_CACHE_TTL_MINUTES:30}}")
    private long bookingCacheTtlMinutes;

    /** Redis key prefix used to namespace booking cache entries. */
    private static final String BOOKING_CACHE_KEY_PREFIX = "resortslite:booking:";

    /**
     * cr-java-0071 FIX: Hardcoded inventory service URL replaced with a value injected
     * from AWS Systems Manager Parameter Store via Spring's @Value mechanism.
     *
     * <p>The property key {@code app.inventory.url} is resolved at startup from
     * {@code application.properties} (or an environment variable override), which in turn
     * should be backed by an SSM Parameter Store entry such as:
     * <pre>
     *   /resortslite/{env}/inventory/url  →  https://inventory-service.internal:8081/rooms/available
     * </pre>
     * This makes the URL environment-agnostic: dev, staging, and production each supply
     * their own SSM parameter value without any code change.
     *
     * <p>Source line 66 (original):
     * {@code String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";}
     */
    @Value("${app.inventory.url:${APP_INVENTORY_URL:https://inventory-service.internal:8081/rooms/available}}")
    private String inventoryUrl;

    /**
     * Creates a new booking and stores the booking state in the distributed Redis session
     * (via Spring Session Data Redis / Amazon ElastiCache) instead of JVM-local HTTP session
     * memory.
     *
     * <p><strong>cr-java-0065 FIX:</strong> {@code session.setAttribute()} calls now persist
     * to Amazon ElastiCache for Redis through the Spring Session filter chain. The
     * {@link HttpSession} parameter is retained — Spring Session transparently replaces the
     * underlying store so no API change is needed at the controller level.
     *
     * <p><strong>cr-java-0067 FIX:</strong> Booking details are cached in Amazon ElastiCache
     * for Redis via {@link RedisTemplate} with a TTL of {@code bookingCacheTtlMinutes} minutes,
     * replacing the previous unbounded in-memory {@code HashMap} that caused indefinite memory
     * growth and was invisible to other application instances.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session attributes are now stored in Amazon ElastiCache for Redis
        // via Spring Session Data Redis (spring.session.store-type=redis). The HttpSession API
        // is unchanged; Spring Session's SessionRepositoryFilter intercepts these calls and
        // serialises the data to the configured Redis cluster, enabling stateless application
        // instances and safe horizontal scaling across multiple EC2/ECS instances.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cr-java-0067 FIX: Cache the booking in Amazon ElastiCache for Redis with an explicit
        // TTL instead of storing it in the unbounded static HashMap. The entry expires
        // automatically after bookingCacheTtlMinutes minutes, preventing indefinite memory
        // growth and ensuring all application instances share the same cache state.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, Duration.ofMinutes(bookingCacheTtlMinutes));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the booking status for the given booking ID, reading the guest name from the
     * distributed Redis session rather than from JVM-local HTTP session memory.
     *
     * <p><strong>cr-java-0065 FIX:</strong> {@code session.getAttribute()} now reads from
     * Amazon ElastiCache for Redis through Spring Session, so the value is consistent
     * regardless of which application instance handles the request.
     *
     * <p><strong>cr-java-0067 FIX:</strong> Booking lookup first checks the Redis cache
     * (with TTL) before falling back to the service layer, replacing the previous
     * instance-local HashMap lookup.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: Session attribute is now retrieved from Amazon ElastiCache for
        // Redis via Spring Session Data Redis. Any instance in the cluster returns the same
        // value because the session is stored centrally, not in per-instance JVM memory.
        String lastGuest = (String) session.getAttribute("guestName");

        // cr-java-0067 FIX: Attempt to retrieve the booking from the Redis cache (with TTL)
        // before falling back to the service layer. This replaces the previous instance-local
        // HashMap lookup that was invisible to other application instances.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: The hardcoded URL "http://inventory-service.internal:8081/rooms/available"
        // (source line 66) has been removed. The URL is now injected via @Value from the
        // app.inventory.url property, which is externalised to AWS Systems Manager Parameter Store.
        // Each deployment environment (dev / staging / prod) supplies its own SSM parameter value,
        // enabling environment-agnostic deployments without code changes.
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
