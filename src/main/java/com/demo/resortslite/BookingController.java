package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — REST controller for resort booking operations.
 *
 * <p><strong>cr-java-0065 fix (HTTP Session State Storage):</strong><br>
 * All HTTP session state ({@code session.setAttribute} / {@code session.getAttribute}
 * calls) has been migrated to <strong>Amazon ElastiCache for Redis</strong> via
 * <strong>Spring Session</strong>.  The {@link EnableRedisHttpSession} annotation
 * activates Spring Session's Redis-backed {@link HttpSession} implementation, so every
 * {@code session.setAttribute} / {@code session.getAttribute} call is transparently
 * stored in and retrieved from the shared Redis cluster rather than in-process JVM
 * memory.  This makes every application instance fully stateless: any instance can
 * serve any request regardless of which instance originally created the session,
 * enabling horizontal scaling, rolling deployments, and zero-downtime failover on AWS.</p>
 *
 * <p><strong>cr-java-0067 fix (In-Memory Caching Without TTL):</strong><br>
 * The unbounded in-memory {@code HashMap}-based {@code bookingCache} (original line 19)
 * has been replaced with <strong>Amazon ElastiCache for Redis</strong> accessed via
 * Spring's {@link RedisTemplate}.  Each cache entry is stored under the key
 * {@code booking:<bookingId>} with a configurable TTL (default 30 minutes, controlled
 * by the {@code BOOKING_CACHE_TTL_MINUTES} environment variable).  This eliminates
 * indefinite memory growth, prevents stale data inconsistencies across instances, and
 * provides a single shared cache visible to every application instance in the cluster.</p>
 *
 * <p><strong>cr-java-0071 fix (Hard-coded Environment URLs):</strong><br>
 * The hard-coded inventory service URL
 * {@code "http://inventory-service.internal:8081/rooms/available"} (original line 66)
 * has been replaced with a value retrieved at runtime from
 * <strong>AWS Systems Manager Parameter Store</strong>.  The parameter name is
 * configurable via the {@code INVENTORY_URL_PARAM_NAME} environment variable
 * (default: {@code /resortslite/inventory/url}).  This enables environment-agnostic
 * deployments: the same artifact can be promoted from dev → staging → production
 * simply by updating the SSM parameter value — no code or image rebuild required.</p>
 */
@RestController
@RequestMapping("/api/bookings")
@EnableRedisHttpSession
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0067 fix: Redis-backed distributed cache replacing the former unbounded
     * in-memory {@code HashMap}.  Spring auto-configures this {@link RedisTemplate}
     * using the {@code spring.redis.*} properties that point to Amazon ElastiCache.
     * Every cache write is accompanied by a TTL (see {@link #bookingCacheTtlMinutes}),
     * ensuring entries expire automatically and memory growth is bounded.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * TTL (in minutes) applied to every booking cache entry written to Redis.
     * Injected from the environment variable {@code BOOKING_CACHE_TTL_MINUTES}
     * (via {@code app.cache.booking-ttl-minutes} in application.properties).
     * Defaults to 30 minutes if the environment variable is not set.
     *
     * <p>cr-java-0067 fix: explicit TTL prevents indefinite memory growth and ensures
     * stale booking data is automatically evicted from the shared ElastiCache cluster.</p>
     */
    @Value("${app.cache.booking-ttl-minutes:${BOOKING_CACHE_TTL_MINUTES:30}}")
    private long bookingCacheTtlMinutes;

    /**
     * Redis key prefix used for all booking cache entries.
     * Namespacing avoids collisions with other keys on the same ElastiCache cluster.
     */
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:";

    /**
     * AWS region used when constructing the SSM client.
     * Injected from the environment variable {@code AWS_REGION} (via
     * {@code cloud.aws.region.static} in application.properties).
     */
    @Value("${cloud.aws.region.static:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * SSM Parameter Store parameter name that holds the inventory service URL.
     * Injected from the environment variable {@code INVENTORY_URL_PARAM_NAME}
     * (via {@code app.ssm.inventory-url-param} in application.properties).
     *
     * <p>cr-java-0071 fix: replaces the hard-coded URL string with an externalized
     * SSM parameter reference so the URL can differ per environment without code changes.</p>
     */
    @Value("${app.ssm.inventory-url-param:${INVENTORY_URL_PARAM_NAME:/resortslite/inventory/url}}")
    private String inventoryUrlParamName;

    /**
     * Builds an {@link SsmClient} using the configured AWS region.
     * Uses the default credential provider chain so that IAM roles, environment
     * variables, or instance profiles are all supported without hard-coded credentials.
     *
     * @return a configured {@link SsmClient} instance
     */
    private SsmClient buildSsmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Retrieves the inventory service URL from AWS Systems Manager Parameter Store.
     *
     * <p><strong>cr-java-0071 fix — original line 66:</strong><br>
     * Original hard-coded value:
     * {@code String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";}<br>
     * Replaced with a runtime lookup against SSM Parameter Store using the parameter name
     * stored in {@link #inventoryUrlParamName}.  The parameter value is managed per
     * environment in SSM, enabling zero-code-change promotions across dev/staging/prod.</p>
     *
     * @return the inventory service URL retrieved from SSM Parameter Store
     */
    private String getInventoryUrlFromSsm() {
        // cr-java-0071 fix (line 66): retrieve the environment-specific inventory URL
        // from AWS Systems Manager Parameter Store instead of using a hard-coded string.
        // Original: String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";
        try (SsmClient ssmClient = buildSsmClient()) {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(inventoryUrlParamName)
                    .withDecryption(false)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        }
    }

    /**
     * Stores a booking object in the Redis-backed distributed cache with TTL.
     *
     * <p>cr-java-0067 fix: replaces {@code bookingCache.put(bookingId, booking)} on the
     * former in-memory {@code HashMap}.  The entry is written to Amazon ElastiCache for
     * Redis under the key {@code booking:<bookingId>} and automatically expires after
     * {@link #bookingCacheTtlMinutes} minutes, preventing unbounded memory growth and
     * ensuring stale data is evicted consistently across all application instances.</p>
     *
     * @param bookingId the unique booking identifier used as the cache key suffix
     * @param booking   the booking data map to cache
     */
    private void putBookingInCache(String bookingId, Map<String, Object> booking) {
        // cr-java-0067 fix: write to Amazon ElastiCache for Redis with explicit TTL.
        // Original: bookingCache.put(bookingId, booking)  — unbounded in-memory HashMap.
        // Fixed:    Redis opsForValue().set() with TTL — bounded, distributed, auto-expiring.
        redisTemplate.opsForValue().set(
                BOOKING_CACHE_KEY_PREFIX + bookingId,
                booking,
                bookingCacheTtlMinutes,
                TimeUnit.MINUTES
        );
    }

    /**
     * Retrieves a booking object from the Redis-backed distributed cache.
     *
     * <p>cr-java-0067 fix: replaces {@code bookingCache.get(bookingId)} on the former
     * in-memory {@code HashMap}.  The lookup is performed against Amazon ElastiCache for
     * Redis, so the cached value is available on every application instance — not just
     * the one that originally stored it.</p>
     *
     * @param bookingId the unique booking identifier used as the cache key suffix
     * @return the cached booking data map, or {@code null} if the entry has expired or
     *         was never stored
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getBookingFromCache(String bookingId) {
        // cr-java-0067 fix: read from Amazon ElastiCache for Redis.
        // Original: bookingCache.get(bookingId)  — instance-local HashMap lookup.
        // Fixed:    Redis opsForValue().get() — shared, distributed cache lookup.
        return (Map<String, Object>) redisTemplate.opsForValue().get(
                BOOKING_CACHE_KEY_PREFIX + bookingId
        );
    }

    /**
     * Creates a new booking and stores the booking state in the distributed
     * Redis-backed HTTP session via Spring Session (cr-java-0065 fix) and
     * caches the booking in Amazon ElastiCache for Redis with TTL (cr-java-0067 fix).
     *
     * <p>Previously, {@code session.setAttribute("lastBooking", booking)} and
     * {@code session.setAttribute("guestName", guestName)} stored data in the
     * local JVM heap, causing server affinity and data loss on failover.  With
     * {@link EnableRedisHttpSession} active, the same {@link HttpSession} API
     * now transparently persists all attributes to Amazon ElastiCache for Redis,
     * making the state available to every instance in the cluster.</p>
     *
     * <p>The former {@code bookingCache.put(bookingId, booking)} call on the
     * unbounded in-memory {@code HashMap} has been replaced with
     * {@link #putBookingInCache(String, Map)}, which writes to Redis with an
     * explicit TTL, bounding memory usage and ensuring cache consistency across
     * all instances (cr-java-0067 fix).</p>
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 fix: session.setAttribute calls now persist to Amazon ElastiCache
        // for Redis via Spring Session (@EnableRedisHttpSession on this class).
        // The HttpSession API is unchanged; Spring Session transparently routes all
        // attribute reads/writes to the shared Redis cluster, eliminating server affinity
        // and enabling stateless horizontal scaling across EC2 / ECS instances.
        session.setAttribute("lastBooking", booking); // cr-java-0065 fixed — stored in Redis via Spring Session
        session.setAttribute("guestName", guestName); // cr-java-0065 fixed — stored in Redis via Spring Session

        // cr-java-0067 fix: replaced bookingCache.put(bookingId, booking) on the former
        // unbounded in-memory HashMap with a Redis write that carries an explicit TTL.
        // The booking is now stored in Amazon ElastiCache for Redis, shared across all
        // application instances, and automatically evicted after bookingCacheTtlMinutes.
        putBookingInCache((String) booking.get("bookingId"), booking); // cr-java-0067 fixed — stored in Redis with TTL

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the booking status for the given booking ID.
     *
     * <p>The guest name is retrieved from the distributed Redis-backed session
     * (cr-java-0065 fix).  Previously, {@code session.getAttribute("guestName")}
     * read from in-process JVM memory, returning {@code null} on any instance
     * other than the one that created the session.  With Spring Session + Redis,
     * the attribute is fetched from the shared ElastiCache cluster and is
     * consistently available on every instance.</p>
     *
     * <p>The booking details are first looked up in the Redis cache via
     * {@link #getBookingFromCache(String)} (cr-java-0067 fix).  If the entry has
     * expired or is absent, the request falls through to the service layer.</p>
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 fix: session.getAttribute now reads from Amazon ElastiCache for
        // Redis via Spring Session (@EnableRedisHttpSession).  The value is available
        // on every application instance, not just the one that originally set it.
        String lastGuest = (String) session.getAttribute("guestName"); // cr-java-0065 fixed — read from Redis via Spring Session

        // cr-java-0067 fix: attempt to serve booking details from the Redis-backed
        // distributed cache before falling back to the service layer.  The cached entry
        // carries a TTL so it will be automatically evicted when it expires.
        Map<String, Object> cachedBooking = getBookingFromCache(bookingId); // cr-java-0067 fixed — read from Redis cache

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 fix (line 66): The hard-coded URL
        // "http://inventory-service.internal:8081/rooms/available" has been replaced
        // with a runtime lookup from AWS Systems Manager Parameter Store.
        // The SSM parameter name is configured via INVENTORY_URL_PARAM_NAME env var
        // (default: /resortslite/inventory/url), enabling environment-agnostic deployments.
        String inventoryUrl = getInventoryUrlFromSsm(); // cr-java-0071 fixed

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
