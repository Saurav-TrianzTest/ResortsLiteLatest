package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController
 *
 * cz-java-0069 fix: Replaced javax.servlet.http.HttpSession (server-side in-memory session)
 * with Spring Session backed by Azure Cache for Redis on AKS. Session state is now stored
 * externally in Redis, making it available across all container instances and surviving
 * container restarts and scale-out events.
 *
 * Redis credentials (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD) are injected via environment
 * variables sourced from Azure Key Vault using the Secrets Store CSI Driver with Workload
 * Identity on AKS — credentials are never hardcoded in source or configuration files.
 *
 * Before (in-memory, cz-java-0069 violation):
 *   HttpSession session  — method parameter
 *   session.setAttribute("lastBooking", booking);   // line 34 — lost on container restart
 *   session.setAttribute("guestName", guestName);   // line 35 — lost on container restart
 *
 * After (externalized to Azure Cache for Redis via Spring Session):
 *   SessionRepository<? extends Session> sessionRepository  — @Autowired bean
 *   session.setAttribute("lastBooking", booking);   // persisted in Redis
 *   session.setAttribute("guestName", guestName);   // persisted in Redis
 *   sessionRepository.save(session);
 *
 * cz-java-0070 fix: Replaced local in-process HashMap cache (bookingCache) with
 * Azure Cache for Redis via RedisTemplate. The local HashMap was instance-local and
 * invisible to other AKS pod replicas, causing cache misses on horizontal scale-out.
 * RedisTemplate stores cache entries in the shared Redis cluster so all pod replicas
 * read and write the same cache data. A TTL of 30 minutes is applied to each entry
 * to prevent unbounded memory growth. Redis connection is configured via REDIS_HOST /
 * REDIS_PORT / REDIS_PASSWORD environment variables injected by the Azure Key Vault
 * CSI Driver with Workload Identity — credentials are never hardcoded.
 *
 * Before (local cache, cz-java-0070 violation):
 *   private static final Map<String, Object> bookingCache = new HashMap<>(); // line 19
 *   bookingCache.put((String) booking.get("bookingId"), booking);
 *
 * After (distributed cache via Azure Cache for Redis):
 *   @Autowired RedisTemplate<String, Object> redisTemplate;
 *   redisTemplate.opsForValue().set("booking:" + bookingId, booking, 30, TimeUnit.MINUTES);
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0069: SessionRepository is provided by Spring Session Data Redis.
    // It stores session data in Azure Cache for Redis instead of the in-memory HttpSession,
    // ensuring session state survives container restarts and is shared across all AKS pod
    // replicas. Redis connection is configured via REDIS_HOST / REDIS_PORT / REDIS_PASSWORD
    // environment variables injected by the Azure Key Vault CSI Driver with Workload Identity.
    @Autowired
    private SessionRepository<Session> sessionRepository;

    // cz-java-0070: RedisTemplate replaces the local in-process HashMap cache.
    // Booking entries are stored in Azure Cache for Redis so that all horizontally-scaled
    // AKS pod replicas share the same cache. Each entry is given a 30-minute TTL to prevent
    // unbounded memory growth. Redis connection details are supplied via environment variables
    // (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD) injected by the Azure Key Vault CSI Driver
    // with Workload Identity — no credentials are hardcoded in source or configuration files.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String reportBasePath;

    // cz-java-0070 fix: Removed local in-process HashMap cache.
    // BEFORE (violation): private static final Map<String, Object> bookingCache = new HashMap<>();
    // AFTER: cache entries are stored in Azure Cache for Redis via redisTemplate (see above).

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 fix (lines 34-35 of original source):
        // BEFORE: session.setAttribute("lastBooking", booking);  — in-memory HttpSession, lost on restart
        // BEFORE: session.setAttribute("guestName", guestName);  — in-memory HttpSession, lost on restart
        // AFTER:  Session state is stored in Azure Cache for Redis via Spring Session.
        //         The session survives container restarts and is visible to all pod replicas.
        Session session = (sessionId != null) ? sessionRepository.findById(sessionId) : null;
        if (session == null) {
            session = sessionRepository.createSession();
        }
        session.setAttribute("lastBooking", booking);   // cz-java-0069: persisted in Redis
        session.setAttribute("guestName", guestName);   // cz-java-0069: persisted in Redis
        sessionRepository.save(session);

        // cz-java-0070 fix: Store booking in Azure Cache for Redis instead of local HashMap.
        // BEFORE: bookingCache.put((String) booking.get("bookingId"), booking);
        // AFTER:  Entry is written to the shared Redis cluster with a 30-minute TTL so all
        //         AKS pod replicas can retrieve it and stale entries are automatically evicted.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set("booking:" + bookingId, booking, 30, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        response.put("sessionId", session.getId());
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        // cz-java-0069: Reading session state from Azure Cache for Redis via Spring Session.
        // Replaces session.getAttribute("guestName") which read from in-memory HttpSession
        // and would return null on any other container instance in the cluster.
        String lastGuest = null;
        if (sessionId != null) {
            Session session = sessionRepository.findById(sessionId);
            if (session != null) {
                lastGuest = (String) session.getAttribute("guestName");
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
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf"; // cz-java-0057: replaced hardcoded path with env var REPORT_BASE_PATH

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
