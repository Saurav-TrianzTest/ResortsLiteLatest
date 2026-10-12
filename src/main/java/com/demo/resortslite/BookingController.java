package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

/**
 * REST controller for resort booking operations.
 *
 * cr-java-0065 FIX — HTTP Session State Storage:
 * All HttpSession usage in this controller is now backed by Amazon ElastiCache for Redis
 * via Spring Session Data Redis (configured in {@link RedisSessionConfig}).
 * The standard {@code HttpSession} API is preserved; Spring Session transparently
 * serialises every session attribute to the shared Redis cluster so that any
 * application instance in the auto-scaling group can serve any request without
 * sticky-session / server-affinity requirements.
 *
 * cr-java-0067 FIX — In-Memory Caching Without TTL:
 * The previous unbounded static {@code HashMap} cache (bookingCache) has been replaced
 * with Amazon ElastiCache for Redis via Spring's {@link RedisTemplate}. Each cache entry
 * is written with a configurable TTL (default 30 minutes, controlled by the
 * {@code BOOKING_CACHE_TTL_MINUTES} environment variable), ensuring controlled expiration,
 * consistent data across all horizontally-scaled instances, and no unbounded memory growth.
 *
 * Session attributes written/read here:
 *   "lastBooking" — the most-recently created booking map for the current user session
 *   "guestName"   — the guest name associated with the current user session
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0067 FIX: RedisTemplate replaces the former unbounded static HashMap cache.
     * All booking cache entries are stored in Amazon ElastiCache for Redis with a TTL,
     * preventing indefinite memory growth and ensuring cache consistency across all
     * application instances in the auto-scaling group.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * TTL (in minutes) for booking cache entries stored in Amazon ElastiCache for Redis.
     * Defaults to 30 minutes; override via the BOOKING_CACHE_TTL_MINUTES environment
     * variable in the ECS task definition, EKS pod spec, or Elastic Beanstalk environment
     * properties to tune expiration per deployment environment.
     */
    @Value("${BOOKING_CACHE_TTL_MINUTES:30}")
    private long bookingCacheTtlMinutes;

    /** Redis key prefix for booking cache entries (cr-java-0067 fix). */
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:cache:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session attributes are now stored in Amazon ElastiCache for Redis
        // via Spring Session Data Redis (@EnableRedisHttpSession in RedisSessionConfig).
        // The HttpSession API is unchanged; Spring Session transparently routes all
        // setAttribute / getAttribute calls to the shared Redis cluster, eliminating
        // server affinity and enabling safe horizontal scaling behind AWS ALB.
        session.setAttribute("lastBooking", booking); // stored in ElastiCache Redis
        session.setAttribute("guestName", guestName); // stored in ElastiCache Redis

        // cr-java-0067 FIX: Cache the booking in Amazon ElastiCache for Redis with a TTL.
        // The former static HashMap (bookingCache) was instance-local and had no expiration,
        // causing stale data and memory growth in multi-instance deployments. RedisTemplate
        // writes the entry to the shared ElastiCache cluster with a configurable TTL so that:
        //   1. All instances share the same cache view (no per-instance inconsistency).
        //   2. Entries expire automatically after bookingCacheTtlMinutes (default 30 min).
        //   3. Memory usage is bounded and managed by ElastiCache eviction policies.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: Session attribute is now retrieved from Amazon ElastiCache for Redis
        // via Spring Session Data Redis. Any instance in the cluster can serve this request
        // because session state is centralised in Redis, not held in local JVM memory.
        String lastGuest = (String) session.getAttribute("guestName"); // retrieved from ElastiCache Redis

        // cr-java-0067 FIX: Retrieve booking details from the ElastiCache Redis cache.
        // Falls back to the BookingService if the entry has expired or is not yet cached.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);
        Object bookingDetails = (cachedBooking != null) ? cachedBooking : bookingService.getBookingById(bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingDetails);
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: Replaced hard-coded environment URL with a value retrieved
        // from AWS Systems Manager Parameter Store. The parameter
        // "/resortslite/inventory/service-url" holds the environment-specific endpoint,
        // enabling environment-agnostic deployments without code changes.
        String inventoryUrl = getSsmParameter("/resortslite/inventory/service-url",
                "http://inventory-service.internal:8081/rooms/available");

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

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     * Falls back to the provided default value if the parameter cannot be retrieved
     * (e.g., during local development where SSM is not available).
     *
     * @param parameterName the SSM parameter name/path
     * @param defaultValue  fallback value used when SSM is unreachable
     * @return the resolved parameter value
     */
    private String getSsmParameter(String parameterName, String defaultValue) {
        try {
            String awsRegion = System.getenv("AWS_REGION") != null
                    ? System.getenv("AWS_REGION") : "us-east-1";
            try (SsmClient ssmClient = SsmClient.builder()
                    .region(Region.of(awsRegion))
                    .build()) {
                GetParameterRequest request = GetParameterRequest.builder()
                        .name(parameterName)
                        .withDecryption(true)
                        .build();
                GetParameterResponse response = ssmClient.getParameter(request);
                return response.parameter().value();
            }
        } catch (Exception e) {
            // Fall back to default value when SSM is not available (e.g., local dev)
            return defaultValue;
        }
    }
}
