package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

/**
 * BookingController — cr-java-0065 and cr-java-0067 FIX applied.
 *
 * cr-java-0065: All HTTP session state is now stored in Amazon ElastiCache for Redis via
 * Spring Session. The {@code HttpSession} API is preserved so that no
 * business logic changes are required; Spring Session transparently
 * serialises every {@code setAttribute}/{@code getAttribute} call to the
 * shared Redis cluster instead of the local JVM heap.
 *
 * cr-java-0067: The unbounded static {@code HashMap} used as an in-memory cache has been
 * replaced with Amazon ElastiCache for Redis via Spring Data Redis {@code RedisTemplate}.
 * A configurable TTL (default 30 minutes) is applied to every cache entry, preventing
 * indefinite memory growth, stale data inconsistencies, and ensuring all application
 * instances share the same centralised cache. This resolves the horizontal-scaling
 * incompatibility where each EC2 instance maintained its own isolated cache copy.
 *
 * Required environment variables (set in ECS task definition / Elastic
 * Beanstalk environment / EC2 user-data):
 *   REDIS_HOST              — ElastiCache primary endpoint  (default: localhost)
 *   REDIS_PORT              — ElastiCache port              (default: 6379)
 *   BOOKING_CACHE_TTL_SECS  — Cache entry TTL in seconds    (default: 1800)
 */
@RestController
@RequestMapping("/api/bookings")
@EnableRedisHttpSession
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0067 FIX: Replaced unbounded static HashMap (in-memory cache without TTL)
     * with Amazon ElastiCache for Redis via Spring Data RedisTemplate.
     *
     * The static HashMap was instance-local, invisible to other EC2 instances, and grew
     * indefinitely causing potential OOM errors and stale data across instances.
     *
     * RedisTemplate backed by ElastiCache provides:
     *  - Centralised cache shared across all application instances
     *  - Configurable TTL (BOOKING_CACHE_TTL_SECS) to prevent indefinite memory growth
     *  - Automatic expiration of stale entries
     *  - Consistent data across horizontal scaling events and instance restarts
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * TTL for booking cache entries in seconds.
     * Configurable via BOOKING_CACHE_TTL_SECS environment variable (default: 1800 = 30 min).
     */
    @Value("${booking.cache.ttl.seconds:1800}")
    private long bookingCacheTtlSeconds;

    /** Redis key prefix for booking cache entries. */
    private static final String BOOKING_CACHE_PREFIX = "bookingCache:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session attributes are now stored in Amazon ElastiCache for
        // Redis via Spring Session. The HttpSession API is unchanged; Spring Session
        // intercepts every setAttribute call and persists the value to the shared Redis
        // cluster, so all application instances share the same session store. AWS ALB
        // sticky sessions are no longer required.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cr-java-0067 FIX: Cache entry is now stored in Amazon ElastiCache for Redis
        // via RedisTemplate with a TTL of bookingCacheTtlSeconds (default 1800 seconds).
        // This replaces the unbounded static HashMap that caused instance-local caching,
        // indefinite memory growth, and stale data across multiple EC2 instances.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: Session attribute is now read from Amazon ElastiCache for
        // Redis via Spring Session. Any instance in the cluster can serve this request
        // and will retrieve the correct value from the shared Redis session store.
        String lastGuest = (String) session.getAttribute("guestName");

        // cr-java-0067 FIX: Booking details are now retrieved from Amazon ElastiCache
        // for Redis via RedisTemplate. All instances share the same cache, so any
        // instance can serve this request with consistent data.
        String cacheKey = BOOKING_CACHE_PREFIX + bookingId;
        @SuppressWarnings("unchecked")
        Map<String, Object> cachedBooking = (Map<String, Object>) redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: Hard-coded environment URL replaced with value retrieved from
        // AWS Systems Manager Parameter Store. The parameter name is read from the
        // environment variable SSM_PARAM_INVENTORY_URL (default:
        // /resortslite/inventory/service-url) so the endpoint can be changed per
        // environment without any code modification.
        String ssmParamName = System.getenv().getOrDefault(
                "SSM_PARAM_INVENTORY_URL", "/resortslite/inventory/service-url");
        String awsRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");

        String inventoryUrl;
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {
            GetParameterResponse paramResponse = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(ssmParamName)
                            .withDecryption(false)
                            .build());
            inventoryUrl = paramResponse.parameter().value();
        }

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
