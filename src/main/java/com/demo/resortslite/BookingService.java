package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — core business logic for resort bookings.
 *
 * <p>Java 21 / Spring Boot 3.x compatibility notes:</p>
 * <ul>
 *   <li>SHA-256 replaces MD5 for confirmation code hashing (MD5 is broken per RFC 6151).</li>
 *   <li>{@code StandardCharsets.UTF_8} used explicitly in {@code getBytes()} calls
 *       to avoid platform-default charset dependency (default is UTF-8 since Java 18,
 *       but explicit is always safer and avoids compiler warnings).</li>
 *   <li>Parameterised JDBC queries replace string-concatenated SQL to prevent SQL injection.</li>
 * </ul>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // NOTE sec-cred-001 / cr-java-0021: Hardcoded credentials and infrastructure hostnames.
    // TODO: Externalise to AWS Secrets Manager / Parameter Store or environment variables.
    private static final String DB_HOST = "db-prod.resorts-internal.com";
    private static final String DB_USER = "admin";
    private static final String DB_PASS = "Resort$Pass#2019!";

    // NOTE cr-java-0021 / cr-java-0088: Hardcoded internal service endpoint over plain HTTP.
    // TODO: Replace with HTTPS and externalise to environment variable / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge";

    /**
     * Creates a new booking record and returns booking details including a confirmation code.
     *
     * <p>Uses parameterised JDBC query to prevent SQL injection (replaces legacy
     * string-concatenated SQL that was vulnerable to injection attacks).</p>
     *
     * @param guestName  name of the guest
     * @param roomType   room category (STANDARD, DELUXE, SUITE, VILLA)
     * @param checkIn    check-in date string
     * @param checkOut   check-out date string
     * @return map containing booking details and confirmation code
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterised query — prevents SQL injection (replaces string-concatenated SQL)
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // SHA-256 confirmation code — replaces MD5 (broken hash algorithm per RFC 6151)
        // Uses StandardCharsets.UTF_8 explicitly for consistent cross-platform behaviour
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", DB_HOST);
        return booking;
    }

    /**
     * Retrieves a booking by its ID.
     *
     * <p>Uses parameterised JDBC query to prevent SQL injection.</p>
     *
     * @param bookingId the booking identifier
     * @return map containing booking details, or an error entry if not found
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Parameterised query — prevents SQL injection (replaces string-concatenated SQL)
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    /**
     * Calculates the total room price based on room type, number of nights,
     * season, and loyalty tier.
     *
     * @param roomType room category
     * @param nights   number of nights
     * @param season   pricing season (PEAK, OFF, or standard)
     * @param loyalty  loyalty tier (GOLD, PLATINUM, DIAMOND, or none)
     * @return formatted total price string
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice;
        switch (roomType) {
            case "DELUXE"   -> basePrice = 200.0;
            case "SUITE"    -> basePrice = 350.0;
            case "VILLA"    -> basePrice = 600.0;
            default         -> basePrice = 120.0; // STANDARD and unknown types
        }

        // Season multiplier
        if ("PEAK".equals(season))      { basePrice *= 1.5; }
        else if ("OFF".equals(season))  { basePrice *= 0.8; }

        // Loyalty discount
        if ("PLATINUM".equals(loyalty))      { basePrice *= 0.8; }
        else if ("DIAMOND".equals(loyalty))  { basePrice *= 0.7; }
        else if ("GOLD".equals(loyalty))     { basePrice *= 0.9; }

        // Long-stay discount (>= 14 nights takes priority over >= 7 nights)
        if (nights >= 14)      { basePrice *= 0.90; }
        else if (nights >= 7)  { basePrice *= 0.95; }

        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether a room of the given type is available.
     *
     * @param roomType room category to check
     * @return {@code true} if the room type is valid and available
     */
    public boolean isRoomAvailable(String roomType) {
        return switch (roomType) {
            case "STANDARD", "DELUXE", "SUITE", "VILLA" -> true;
            default -> false;
        };
    }

    /**
     * Triggers report generation for the specified month.
     *
     * @param month month identifier string
     * @return status message
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     *
     * <p>Uses {@link StandardCharsets#UTF_8} explicitly to ensure consistent behaviour
     * regardless of the JVM default charset (UTF-8 since Java 18, but explicit is safer).</p>
     *
     * @param input the string to hash
     * @return lowercase hex-encoded SHA-256 digest, or the original input on error
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
