package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0065 FIX: RedisTemplate replaces HttpSession for distributed session state.
    // Azure Cache for Redis stores session data externally, enabling stateless horizontal
    // scaling across multiple application instances without server affinity.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cr-java-0067 FIX: Removed static in-memory bookingCache (HashMap without TTL).
    // All cache operations now use Azure Cache for Redis via RedisTemplate with explicit
    // TTL policies, ensuring distributed cache consistency across all application instances,
    // preventing memory exhaustion, and eliminating stale data across cloud nodes.

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Booking state stored in Azure Cache for Redis instead of
        // HTTP session memory. Redis is shared across all application instances, so
        // Azure Load Balancer can route requests to any instance without data loss.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set("session:lastBooking:" + bookingId, booking, 30, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set("session:guestName:" + bookingId, guestName, 30, TimeUnit.MINUTES);

        // cr-java-0067 FIX: Booking data cached in Azure Cache for Redis with a 60-minute TTL.
        // Replaces the former static HashMap (bookingCache) which had no expiration policy,
        // caused unbounded memory growth, and was invisible to other cloud instances.
        // Redis provides a shared, distributed cache with automatic key expiration across
        // all horizontally scaled application instances.
        redisTemplate.opsForValue().set("cache:booking:" + bookingId, booking, 60, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        response.put("sessionKey", bookingId);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // cr-java-0065 FIX: Reading business state from Azure Cache for Redis instead of
        // HTTP session — Redis is accessible from every instance in the cluster, so
        // any node can serve this request regardless of which instance handled the original booking.
        String lastGuest = (String) redisTemplate.opsForValue().get("session:guestName:" + bookingId);

        // cr-java-0067 FIX: Booking lookup served from Azure Cache for Redis distributed cache.
        // Falls back to the booking service if the cache entry has expired (TTL elapsed),
        // ensuring correctness without relying on instance-local in-memory state.
        Object cachedBooking = redisTemplate.opsForValue().get("cache:booking:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
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
