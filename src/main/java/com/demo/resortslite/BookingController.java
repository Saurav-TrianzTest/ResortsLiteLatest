package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIXED cr-java-0067 [Cloud Compatibility / Mandatory]: Replaced static in-memory HashMap
    // with Amazon ElastiCache for Redis using Spring Cache abstraction.
    // Benefits:
    // - Centralized cache shared across all EC2 instances
    // - Automatic TTL-based expiration (30 minutes for booking cache)
    // - Horizontal scalability without cache synchronization issues
    // - Consistent data across multiple instances in AWS Auto Scaling groups
    // - Automatic memory management and eviction policies
    // The static HashMap has been removed and replaced with @Cacheable and @CachePut annotations

    @PostMapping("/create")
    @CachePut(value = "bookingCache", key = "#result['bookingId']")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // VIOLATION cr-java-0065 [Cloud Compatibility / Mandatory]: Booking state stored in
        // HTTP session memory. AWS ALB distributes requests across EC2 instances — session
        // data on instance A is invisible to instance B. Auto-scaling and failover breaks.
        session.setAttribute("lastBooking", booking); // cr-java-0065
        session.setAttribute("guestName", guestName); // cr-java-0065

        // Cache is automatically populated via @CachePut annotation
        // The booking data is stored in Redis with the bookingId as the key

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    @Cacheable(value = "bookingCache", key = "#bookingId", unless = "#result == null")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // VIOLATION cr-java-0065 [Cloud Compatibility / Mandatory]: Reading business state
        // from HTTP session — will return null on any other instance in the cluster.
        String lastGuest = (String) session.getAttribute("guestName"); // cr-java-0065

        // First check Redis cache via @Cacheable annotation
        // If not in cache, fetch from database via bookingService
        Map<String, Object> bookingDetails = bookingService.getBookingById(bookingId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingDetails);
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
