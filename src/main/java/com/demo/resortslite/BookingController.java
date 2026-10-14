package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * cz-java-0069 FIX: In-Memory Session Storage → Amazon ElastiCache (Redis)
 *
 * HttpSession is now transparently backed by Amazon ElastiCache (Redis) via
 * Spring Session Data Redis (@EnableRedisHttpSession in RedisSessionConfig).
 * No changes to the HttpSession API calls are required — Spring Session
 * intercepts the standard HttpSession interface and stores/retrieves all
 * session attributes in Redis, enabling:
 *   - Stateless, horizontally-scalable EKS pods (no sticky sessions needed)
 *   - Session persistence across container restarts and pod replacements
 *   - Shared session state across all instances in the cluster
 *
 * Redis connection is configured via environment variables:
 *   REDIS_HOST — ElastiCache primary endpoint (default: localhost)
 *   REDIS_PORT — ElastiCache port             (default: 6379)
 *
 * cz-java-0070 FIX: Local In-Process Cache → Amazon ElastiCache (Redis)
 *
 * The static HashMap bookingCache has been removed and replaced with
 * Spring Cache annotations (@CachePut / @Cacheable) backed by the
 * RedisCacheManager configured in RedisCacheConfig. This ensures:
 *   - Cache entries are stored in Amazon ElastiCache (Redis), not JVM heap
 *   - All horizontally-scaled EKS pods share the same cache view
 *   - Cache TTL is enforced centrally (see RedisCacheConfig)
 *   - No stale or inconsistent cache state across container instances
 *
 * Cache connection re-uses the same ElastiCache endpoint as Spring Session:
 *   REDIS_HOST — ElastiCache primary endpoint (default: localhost)
 *   REDIS_PORT — ElastiCache port             (default: 6379)
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0057: Absolute file path replaced with environment variable injection
    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String reportBasePath;

    // cz-java-0070 FIX: The static local HashMap cache has been removed.
    // Booking entries are now stored in Amazon ElastiCache (Redis) via
    // Spring Cache annotations (@CachePut on createBooking, @Cacheable on
    // getBookingStatus). RedisCacheManager (see RedisCacheConfig) wires
    // Spring's cache abstraction to the shared ElastiCache Redis cluster,
    // making the cache visible to every EKS pod in the deployment.

    @PostMapping("/create")
    // cz-java-0070 FIX: @CachePut stores the returned booking map in the
    // "bookings" Redis cache under the key returned by bookingId.
    // Unlike the old HashMap.put(), this write goes to ElastiCache so every
    // pod in the cluster can read it — no instance-local state.
    @CachePut(value = "bookings", key = "#result['bookingId']")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 FIX (Line 34 — original source): session.setAttribute now persists
        // to Amazon ElastiCache (Redis) via Spring Session Data Redis. Session data is
        // shared across all EKS container instances — no sticky sessions required.
        // RedisSessionConfig (@EnableRedisHttpSession) transparently intercepts this call.
        session.setAttribute("lastBooking", booking); // cz-java-0069: Redis-backed via Spring Session
        // cz-java-0069 FIX (Line 35 — original source): same transparent Redis-backed
        // session storage — guestName is stored in ElastiCache, not in JVM heap memory.
        session.setAttribute("guestName", guestName); // cz-java-0069: Redis-backed via Spring Session

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    // cz-java-0070 FIX: @Cacheable reads the booking from the "bookings" Redis
    // cache before delegating to bookingService. Any EKS pod can serve this
    // request and retrieve the correct cached entry from the shared Redis store.
    @Cacheable(value = "bookings", key = "#bookingId")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cz-java-0069 FIX: session.getAttribute now reads from Amazon ElastiCache (Redis)
        // via Spring Session Data Redis. Any EKS pod can serve this request and retrieve
        // the correct session data from the shared Redis store.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
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
        // cz-java-0057: Replaced hardcoded absolute path with environment variable
        // REPORT_BASE_PATH is injected via Kubernetes ConfigMap / EKS Pod spec env var
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
