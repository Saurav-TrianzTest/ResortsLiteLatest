package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native REST controller for resort booking operations.
 *
 * cr-java-0065 FIX: All HTTP session state (HttpSession) has been replaced with
 * Spring Session backed by Amazon ElastiCache for Redis.  The SessionRepository<S>
 * bean is auto-configured by spring-session-data-redis and stores session data in
 * the Redis cluster, making every application instance fully stateless.  AWS ALB
 * can distribute requests across any number of EC2 / ECS instances without sticky
 * sessions, and Auto Scaling / failover no longer causes session data loss.
 *
 * cr-java-0067 FIX: The unbounded static in-memory HashMap (bookingCache) has been
 * replaced with Amazon ElastiCache for Redis via Spring's RedisTemplate.  Each cache
 * entry is stored under the key "booking:<bookingId>" with a configurable TTL
 * (default 30 minutes), preventing indefinite memory growth, stale data, and the
 * instance-local visibility problem that breaks horizontal scaling on AWS.
 *
 * Session lifecycle:
 *   - createBooking  → creates (or reuses) a Redis-backed session and writes
 *                      "lastBooking" and "guestName" attributes.
 *   - getBookingStatus → reads "guestName" from the same Redis-backed session
 *                        using the session-id supplied by the caller.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0067 FIX: Replaces the former unbounded static in-memory HashMap:
     *   private static final Map<String, Object> bookingCache = new HashMap<>();
     *
     * RedisTemplate<String, Object> is auto-configured by spring-boot-starter-data-redis
     * and backed by Amazon ElastiCache for Redis.  Cache entries are stored with an
     * explicit TTL (see BOOKING_CACHE_TTL_SECONDS) so they expire automatically,
     * preventing indefinite memory growth and stale data.  Because the cache lives in
     * ElastiCache — not in JVM heap — every application instance in the cluster shares
     * the same view of cached bookings, enabling safe horizontal scaling on AWS.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * TTL (in seconds) for booking cache entries stored in Amazon ElastiCache for Redis.
     * Configurable via the {@code BOOKING_CACHE_TTL_SECONDS} environment variable or the
     * {@code app.cache.booking-ttl-seconds} property.  Defaults to 1800 seconds (30 min).
     *
     * cr-java-0067 FIX: Explicit TTL ensures cache entries expire automatically, preventing
     * indefinite memory growth and stale data inconsistencies across multiple instances.
     */
    @Value("${app.cache.booking-ttl-seconds:${BOOKING_CACHE_TTL_SECONDS:1800}}")
    private long bookingCacheTtlSeconds;

    /**
     * cr-java-0065 FIX: Spring Session repository backed by Amazon ElastiCache for Redis.
     * Auto-configured by spring-session-data-redis; no manual bean definition required.
     * The wildcard type parameter <S extends Session> is resolved at runtime to
     * RedisIndexedSessionRepository.RedisSession (or ReactiveRedisSessionRepository
     * depending on the classpath).
     */
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    @Autowired
    private SessionRepository<? extends Session> sessionRepository;

    /**
     * AWS SSM client used to retrieve environment-specific URLs from Parameter Store.
     * cr-java-0071 fix: replaces hard-coded environment URLs with SSM Parameter Store lookups.
     */
    @Autowired
    private SsmClient ssmClient;

    /**
     * SSM parameter name for the inventory service URL.
     * Resolved from the {@code SSM_PARAM_INVENTORY_URL} environment variable or the
     * {@code app.ssm.param.inventory-url} property.
     * Default: {@code /resortslite/inventory/service-url}
     */
    @Value("${app.ssm.param.inventory-url:${SSM_PARAM_INVENTORY_URL:/resortslite/inventory/service-url}}")
    private String inventoryUrlParamName;

    /** Redis key prefix for booking cache entries (cr-java-0067 fix). */
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:";

    /**
     * Create a new booking and persist session state to Amazon ElastiCache for Redis.
     *
     * cr-java-0065 FIX: session.setAttribute() calls on the local HttpSession have been
     * replaced with equivalent calls on a Redis-backed Spring Session.  The session ID
     * is returned to the caller so subsequent requests can reference the same session
     * regardless of which application instance handles them.
     *
     * cr-java-0067 FIX: bookingCache.put() on the static HashMap has been replaced with
     * redisTemplate.opsForValue().set() with an explicit TTL, storing the booking in
     * Amazon ElastiCache for Redis so all instances share the same cache.
     *
     * @param guestName  guest's full name
     * @param roomType   requested room category
     * @param checkIn    check-in date (ISO-8601)
     * @param checkOut   check-out date (ISO-8601)
     * @param sessionId  (optional) existing Spring Session ID; a new session is created
     *                   when blank or absent
     * @return confirmation map including booking details and the Redis session ID
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Retrieve an existing Redis-backed session or create a new one.
        // All session data is stored in Amazon ElastiCache for Redis, not in JVM heap.
        // This makes the application instance fully stateless and safe for horizontal scaling.
        Session session = (sessionId != null && !sessionId.isEmpty())
                ? sessionRepository.findById(sessionId)
                : null;
        if (session == null) {
            session = sessionRepository.createSession();
        }

        // cr-java-0065 FIX: Attributes written to the Redis-backed session — replaces the
        // former HttpSession.setAttribute("lastBooking", ...) and
        // HttpSession.setAttribute("guestName", ...) calls that caused server affinity.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);
        sessionRepository.save(session);

        // cr-java-0067 FIX: Replaces the former unbounded in-memory cache entry:
        //   bookingCache.put((String) booking.get("bookingId"), booking);
        //
        // The booking is now stored in Amazon ElastiCache for Redis under the key
        // "booking:<bookingId>" with an explicit TTL (bookingCacheTtlSeconds, default 30 min).
        // This ensures:
        //   1. Controlled expiration — no indefinite memory growth or stale data.
        //   2. Shared visibility — all application instances read from the same cache.
        //   3. Cloud-native scalability — cache survives instance restarts and scale-out events.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set(
                BOOKING_CACHE_KEY_PREFIX + bookingId,
                booking,
                bookingCacheTtlSeconds,
                TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        // Return the Redis session ID so the client can pass it on subsequent requests.
        response.put("sessionId", session.getId());
        return response;
    }

    /**
     * Retrieve booking status, reading guest context from the distributed Redis session.
     *
     * cr-java-0065 FIX: The former HttpSession.getAttribute("guestName") call has been
     * replaced with a lookup against the Redis-backed Spring Session identified by the
     * caller-supplied sessionId.  Any application instance in the cluster can serve this
     * request because session data lives in ElastiCache, not in local JVM memory.
     *
     * @param bookingId unique booking identifier
     * @param sessionId Spring Session ID returned by /create (optional)
     * @return booking status map including the guest name from the distributed session
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false) String sessionId) {

        // cr-java-0065 FIX: Read "guestName" from the Redis-backed distributed session.
        // Replaces the former HttpSession.getAttribute("guestName") that was instance-local
        // and would return null when the request landed on a different EC2 instance.
        String lastGuest = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            Session session = sessionRepository.findById(sessionId);
            if (session != null) {
                lastGuest = session.getAttribute("guestName");
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: Hard-coded URL "http://inventory-service.internal:8081/rooms/available"
        // replaced with a runtime lookup from AWS Systems Manager Parameter Store.
        // The parameter name is externalised via the 'app.ssm.param.inventory-url' property
        // (or SSM_PARAM_INVENTORY_URL env var), defaulting to /resortslite/inventory/service-url.
        // This makes the endpoint environment-agnostic: dev, staging, and production each store
        // their own value in SSM under the same parameter name, with no code changes required.
        GetParameterRequest paramRequest = GetParameterRequest.builder()
                .name(inventoryUrlParamName)
                .withDecryption(false)
                .build();
        GetParameterResponse paramResponse = ssmClient.getParameter(paramRequest);
        String inventoryUrl = paramResponse.parameter().value();

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
