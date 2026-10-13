package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// FIXED: JAVA8_TO_25_CRYPTOGRAPHY_AND_TLS — replaced MD5 with SHA-256.
// MD5 is a broken algorithm (RFC 6151) and is disabled in Java 25 security policy.
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // NOTE: Credentials and infrastructure endpoints should be externalised to
    // environment variables or a secrets manager (e.g., AWS Secrets Manager).
    private static final String DB_HOST = "db-prod.resorts-internal.com";
    private static final String DB_USER = "admin";
    private static final String DB_PASS = "Resort$Pass#2019!";
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge";

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // FIXED: sql-inject-001 — replaced string-concatenated SQL with parameterised
        // JdbcTemplate update to prevent SQL injection attacks.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // FIXED: JAVA8_TO_25_CRYPTOGRAPHY_AND_TLS / sec-weak-hash-001 — replaced broken
        // MD5 algorithm with SHA-256 (MD5 is disabled by Java 25 security policy).
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
        // FIXED: sql-inject-001 — replaced string-concatenated SQL with parameterised
        // JdbcTemplate queryForMap call to prevent SQL injection attacks.
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice;
        switch (roomType) {
            case "DELUXE"   -> basePrice = 200.0;
            case "SUITE"    -> basePrice = 350.0;
            case "VILLA"    -> basePrice = 600.0;
            default         -> basePrice = 120.0; // STANDARD or unknown
        }
        if ("PEAK".equals(season))      { basePrice *= 1.5; }
        else if ("OFF".equals(season))  { basePrice *= 0.8; }

        if ("GOLD".equals(loyalty))         { basePrice *= 0.9; }
        else if ("PLATINUM".equals(loyalty)) { basePrice *= 0.8; }
        else if ("DIAMOND".equals(loyalty))  { basePrice *= 0.7; }

        if (nights >= 14)      { basePrice *= 0.90; }
        else if (nights >= 7)  { basePrice *= 0.95; }

        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    public boolean isRoomAvailable(String roomType) {
        return "STANDARD".equals(roomType) || "DELUXE".equals(roomType)
                || "SUITE".equals(roomType) || "VILLA".equals(roomType);
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    // FIXED: JAVA8_TO_25_CRYPTOGRAPHY_AND_TLS — SHA-256 replaces the former md5Hash.
    // SHA-256 is the minimum recommended digest for non-password hashing.
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
