package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// FIXED (issue-4 / JAVA8_TO_21_SECURITY_CHANGES): Replaced MD5 (broken cryptographic hash,
// RFC 6151) with SHA-256. MD5 is vulnerable to collision attacks and is no longer considered
// cryptographically secure. SHA-256 is the recommended replacement for confirmation codes.
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // NOTE: Credentials and infrastructure hostnames should be externalised to
    // environment variables or AWS Secrets Manager / Parameter Store.
    private static final String DB_HOST = "db-prod.resorts-internal.com";
    private static final String DB_USER = "admin";
    private static final String DB_PASS = "Resort$Pass#2019!";
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge";

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // NOTE: SQL built by string concatenation is vulnerable to SQL injection.
        // Replace with parameterised queries using JdbcTemplate '?' placeholders.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ('"
                + bookingId + "', '" + guestName + "', '" + roomType
                + "', '" + checkIn + "', '" + checkOut + "')";
        jdbcTemplate.execute(sql);

        // FIXED (issue-4): Using SHA-256 instead of MD5 for confirmation code generation.
        // FIXED (issue-8): Explicitly specifying UTF-8 charset on getBytes() — Java 18+
        // changed the default charset behaviour; explicit charset ensures consistency.
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

    public Map<String, Object> getBookingById(String bookingId) {
        // NOTE: SQL injection risk — replace with parameterised query.
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = switch (roomType) {
            case "STANDARD" -> 120.0;
            case "DELUXE"   -> 200.0;
            case "SUITE"    -> 350.0;
            case "VILLA"    -> 600.0;
            default         -> 120.0;
        };

        if ("PEAK".equals(season))      { basePrice *= 1.5; }
        else if ("OFF".equals(season))  { basePrice *= 0.8; }

        if ("GOLD".equals(loyalty))         { basePrice *= 0.9; }
        else if ("PLATINUM".equals(loyalty)) { basePrice *= 0.8; }
        else if ("DIAMOND".equals(loyalty))  { basePrice *= 0.7; }

        // NOTE: nights >= 14 check must come before >= 7 to apply the larger discount.
        if (nights >= 14)      { basePrice *= 0.90; }
        else if (nights >= 7)  { basePrice *= 0.95; }

        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    public boolean isRoomAvailable(String roomType) {
        return switch (roomType) {
            case "STANDARD", "DELUXE", "SUITE", "VILLA" -> true;
            default -> false;
        };
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    /**
     * FIXED (issue-4 / JAVA8_TO_21_SECURITY_CHANGES): Computes a SHA-256 hex digest.
     * Replaced the previous MD5 implementation (broken hash, RFC 6151) with SHA-256.
     * FIXED (issue-8 / JAVA8_TO_21_UTF8_DEFAULT_CHARSET): Uses StandardCharsets.UTF_8
     * explicitly on getBytes() to ensure consistent behaviour across all Java versions.
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
