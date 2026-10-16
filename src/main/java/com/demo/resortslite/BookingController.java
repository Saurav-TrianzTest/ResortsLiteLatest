package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * BookingController — REST API for resort booking operations.
 *
 * <p>Jakarta EE migration: uses {@code jakarta.servlet.http.HttpSession}
 * (replaces the legacy {@code javax.servlet.http.HttpSession} from Java EE / Spring Boot 2.x).
 * This is required for Spring Boot 3.x / Jakarta EE 10 compatibility with Java 21.</p>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // NOTE cr-java-0067: In-memory cache without TTL breaks horizontal scaling.
    // Cache is instance-local and invisible to other instances.
    // TODO: Replace with a distributed cache (e.g., Redis / ElastiCache) for cloud deployment.
    private static final Map<String, Object> bookingCache = new HashMap<>();

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // NOTE cr-java-0065: Booking state stored in HTTP session memory.
        // AWS ALB distributes requests across instances — session data on instance A is
        // invisible to instance B. TODO: Externalise session to Redis / DynamoDB.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        bookingCache.put((String) booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // NOTE cr-java-0065: Reading business state from HTTP session.
        // Will return null on any other instance in the cluster.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // NOTE cr-java-0088: Plain HTTP call to internal inventory service.
        // TODO: Replace with HTTPS endpoint for cloud-native / AWS Well-Architected compliance.
        String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // NOTE czr-java-001: Hardcoded absolute file path.
        // TODO: Replace with cloud object storage (S3 / Azure Blob) or environment variable.
        String reportPath = "/var/legacy/reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
