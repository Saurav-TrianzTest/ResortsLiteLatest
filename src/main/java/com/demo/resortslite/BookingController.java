package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cr-java-0065 FIX: javax.servlet.http.HttpSession is retained as the API surface, but
// Spring Session Data Redis (spring-session-data-redis) transparently replaces the
// in-process HttpSession store with Amazon ElastiCache for Redis.  Every
// session.setAttribute / session.getAttribute call is now serialised to Redis so that
// any EC2 instance in the Auto Scaling Group can read the same session data, enabling
// true stateless horizontal scaling without sticky sessions on the ALB.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 FIX: Static in-memory HashMap replaced with Amazon ElastiCache for Redis
    // via Spring Data Redis RedisTemplate. The cache is now centralised, shared across all
    // EC2 instances, and enforces a TTL so entries expire automatically — preventing
    // unbounded memory growth and stale data inconsistencies in horizontally-scaled deployments.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // TTL for booking cache entries in Amazon ElastiCache for Redis (seconds).
    // Defaults to 3600 s (1 hour). Override with BOOKING_CACHE_TTL_SECONDS env var.
    @Value("${app.cache.booking.ttl-seconds:${BOOKING_CACHE_TTL_SECONDS:3600}}")
    private long bookingCacheTtlSeconds;

    // Redis key prefix for booking cache entries — avoids key collisions with other
    // namespaces stored in the same ElastiCache cluster.
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:cache:";

    // cr-java-0071 FIX: Inventory service URL externalized via AWS Systems Manager Parameter Store.
    // The value is resolved at startup from the SSM parameter /resortslite/inventory/service-url
    // (configured in application.properties as app.inventory.service-url).
    // Set the environment variable APP_INVENTORY_SERVICE_URL or configure the SSM parameter
    // /resortslite/inventory/service-url to override the default for each deployment environment.
    @Value("${app.inventory.service-url:https://inventory-service.internal:8081/rooms/available}")
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
        // via Spring Session Data Redis.  The HttpSession API is unchanged; Spring Session
        // intercepts all setAttribute/getAttribute calls and delegates to the Redis store,
        // so session data is shared across all application instances behind the AWS ALB.
        // No sticky-session (AWSALB cookie) is required on the load balancer.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cr-java-0067 FIX: Booking entry stored in Amazon ElastiCache for Redis with TTL.
        // RedisTemplate.opsForValue().set() writes the booking object to the centralised
        // Redis cache with an explicit expiry (bookingCacheTtlSeconds). This replaces the
        // unbounded static HashMap that was instance-local and had no expiration policy.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
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

        // cr-java-0065 FIX: Session data is now read from Amazon ElastiCache for Redis
        // via Spring Session Data Redis.  Any EC2 instance handling this request will
        // retrieve the same "guestName" value regardless of which instance originally
        // stored it, eliminating the server-affinity problem caused by in-process
        // HttpSession storage.
        String lastGuest = (String) session.getAttribute("guestName");

        // cr-java-0067 FIX: Booking details retrieved from Amazon ElastiCache for Redis.
        // RedisTemplate.opsForValue().get() fetches the cached booking from the centralised
        // Redis store. Returns null (cache miss) if the entry has expired per the TTL policy
        // or was never cached — callers fall back to bookingService.getBookingById().
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
        // cr-java-0071 FIX: inventoryServiceUrl is no longer hard-coded.
        // It is injected from AWS SSM Parameter Store via Spring's @Value binding
        // (property key: app.inventory.service-url). The SSM parameter
        // /resortslite/inventory/service-url holds the environment-specific endpoint,
        // allowing the same artifact to be deployed across dev, staging, and production
        // without any code changes.
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
