package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // VIOLATION [Security Health / Critical]: Hardcoded database credentials in source code.
    // Must be externalised to AWS Secrets Manager or Parameter Store.
    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021
    private static final String DB_USER = "admin";                         // sec-cred-001
    private static final String DB_PASS = "Resort$Pass#2019!";             // sec-cred-001

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure hostname.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // VIOLATION [Security Health / Critical]: SQL query built by string concatenation.
        // Use parameterised queries (JdbcTemplate with '?') to prevent SQL injection.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ('"  // sql-inject-001
                + bookingId + "', '" + guestName + "', '" + roomType                        // sql-inject-001
                + "', '" + checkIn + "', '" + checkOut + "')";                              // sql-inject-001
        jdbcTemplate.execute(sql);

        // UPGRADED: SHA-256 replaces MD5 for confirmation code hashing.
        // MD5 is a broken algorithm (RFC 6151) and Java 25 security policy disables it
        // in many contexts (jdk.security.legacyAlgorithms). SHA-256 is the minimum
        // acceptable digest for new code per NIST SP 800-131A.
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
        // VIOLATION [Security Health / Critical]: SQL injection via string concatenation.
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'"; // sql-inject-001
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice;
        if (roomType.equals("STANDARD"))      { basePrice = 120.0; }
        else if (roomType.equals("DELUXE"))   { basePrice = 200.0; }
        else if (roomType.equals("SUITE"))    { basePrice = 350.0; }
        else if (roomType.equals("VILLA"))    { basePrice = 600.0; }
        else                                  { basePrice = 120.0; }

        if (season.equals("PEAK"))            { basePrice = basePrice * 1.5; }
        else if (season.equals("OFF"))        { basePrice = basePrice * 0.8; }

        if (loyalty.equals("GOLD"))           { basePrice = basePrice * 0.9; }
        else if (loyalty.equals("PLATINUM"))  { basePrice = basePrice * 0.8; }
        else if (loyalty.equals("DIAMOND"))   { basePrice = basePrice * 0.7; }

        if (nights >= 14)                     { basePrice = basePrice * 0.90; }
        else if (nights >= 7)                 { basePrice = basePrice * 0.95; }

        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    public boolean isRoomAvailable(String roomType) {
        return roomType.equals("STANDARD") || roomType.equals("DELUXE")
                || roomType.equals("SUITE") || roomType.equals("VILLA");
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    /**
     * UPGRADED: Replaced md5Hash with sha256Hash.
     * MD5 is disabled by Java 25 security policy in many contexts (jdk.security.legacyAlgorithms).
     * SHA-256 is the minimum recommended digest algorithm per NIST SP 800-131A.
     *
     * <p>The input bytes are explicitly encoded as UTF-8 (StandardCharsets.UTF_8) to avoid
     * relying on the platform default charset, which was locale-dependent in Java 8 but
     * standardised to UTF-8 in Java 18 — making the hash non-deterministic across environments
     * when using the old {@code String.getBytes()} overload without a charset argument.</p>
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // UPGRADED: Explicit UTF-8 charset instead of platform default.
            // Ensures deterministic behaviour across all environments and JDK versions
            // (rule JAVA8_TO_25_UTF8_DEFAULT_CHARSET).
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
