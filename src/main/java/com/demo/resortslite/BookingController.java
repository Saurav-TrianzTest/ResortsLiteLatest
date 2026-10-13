package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0069: HttpSession is now backed by Spring Session + Amazon ElastiCache (Redis).
// @EnableRedisHttpSession on ResortsLiteApplication transparently replaces the in-memory
// session store with a Redis-backed store, making sessions container-restart-safe and
// horizontally scalable across multiple EKS pod instances.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0070: Replaced local in-memory HashMap cache with Amazon ElastiCache (Redis)
    // via RedisTemplate. Connection details (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD) are
    // injected through Kubernetes ConfigMaps and Secrets, enabling horizontally scalable,
    // container-safe caching shared across all EKS pod replicas.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Injected from environment variable / Kubernetes ConfigMap (cz-java-0057)
    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String reportBasePath;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // cz-java-0069: HttpSession is transparently backed by Spring Session + Redis
            // (Amazon ElastiCache). Session data is stored externally and survives container
            // restarts and is shared across all EKS pod replicas.
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069: Session attributes are now persisted to Amazon ElastiCache (Redis)
        // via Spring Session — safe for horizontal scaling and container restarts.
        // Spring Session (spring-session-data-redis) + @EnableRedisHttpSession intercepts
        // all HttpSession calls and delegates storage to the configured Redis (ElastiCache) cluster.
        session.setAttribute("lastBooking", booking); // cz-java-0069: externalized to Redis via Spring Session
        session.setAttribute("guestName", guestName); // cz-java-0069: externalized to Redis via Spring Session

        // cz-java-0070: Store booking in Amazon ElastiCache (Redis) instead of local HashMap.
        // RedisTemplate delegates to the ElastiCache cluster configured via REDIS_HOST/REDIS_PORT
        // environment variables, ensuring cache consistency across all EKS pod replicas.
        redisTemplate.opsForValue().set("booking:" + booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // cz-java-0069: HttpSession is transparently backed by Spring Session + Redis
            // (Amazon ElastiCache). Reading session attributes is now consistent across
            // all EKS pod replicas — no stale or missing data on cross-instance requests.
            HttpSession session) {

        // cz-java-0069: Session attribute read is now served from Amazon ElastiCache (Redis)
        // via Spring Session — consistent across all instances in the EKS cluster.
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
        // cz-java-0057: Replaced hardcoded absolute path with environment variable REPORT_BASE_PATH
        // injected via Kubernetes ConfigMap / EKS Pod spec environment variable.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
