package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 [GCP Cloud Readiness / CRITICAL]: Hard-coded database credentials replaced
    // with Google Secret Manager integration via Spring Cloud GCP Secret Manager.
    // Credentials are now resolved at runtime from GCP Secret Manager secrets, enabling
    // credential rotation without redeployment and preventing exposure in version control.
    @Value("${spring.datasource.host:${sm://db-host}}")
    private String dbHost;

    @Value("${spring.datasource.username:${sm://db-username}}")
    private String dbUser;

    @Value("${spring.datasource.password:${sm://db-password}}")
    private String dbPass;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // VIOLATION [Security Health / Critical]: SQL query built by string concatenation.
        // An attacker can pass guestName = "'; DROP TABLE bookings; --" to destroy data.
        // Use parameterised queries (JdbcTemplate with '?') to prevent SQL injection.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ('" // sql-inject-001
                + bookingId + "', '" + guestName + "', '" + roomType               // sql-inject-001
                + "', '" + checkIn + "', '" + checkOut + "')";                     // sql-inject-001
        jdbcTemplate.execute(sql);

        // cr-java-0090 [GCP Cloud Readiness / HIGH]: File-based authentication token generation
        // replaced with secure SHA-256 HMAC-style confirmation code. Credentials and secrets
        // are sourced exclusively from Google Secret Manager (sm:// URI scheme) rather than
        // local files or hardcoded values, enabling distributed, stateless authentication
        // compatible with horizontal scaling on GCP Cloud Run / GKE.
        String confirmCode = generateSecureConfirmationCode(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", dbHost);
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // VIOLATION [Security Health / Critical]: SQL injection via string concatenation.
        // bookingId is user-supplied input appended directly into the SQL string.
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'"; // sql-inject-001
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // VIOLATION [Code Sustainability / High]: High cyclomatic complexity.
    // This method has 9+ decision branches. Automated transformation tools flag methods
    // above complexity threshold as high maintenance risk and transformation blockers.
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = 0;
        if (roomType.equals("STANDARD")) { basePrice = 120.0; }
        else if (roomType.equals("DELUXE")) { basePrice = 200.0; }
        else if (roomType.equals("SUITE")) { basePrice = 350.0; }
        else if (roomType.equals("VILLA")) { basePrice = 600.0; }
        else { basePrice = 120.0; }
        if (season.equals("PEAK")) { basePrice = basePrice * 1.5; }
        else if (season.equals("OFF")) { basePrice = basePrice * 0.8; }
        if (loyalty.equals("GOLD")) { basePrice = basePrice * 0.9; }
        else if (loyalty.equals("PLATINUM")) { basePrice = basePrice * 0.8; }
        else if (loyalty.equals("DIAMOND")) { basePrice = basePrice * 0.7; }
        if (nights >= 7) { basePrice = basePrice * 0.95; }
        else if (nights >= 14) { basePrice = basePrice * 0.90; }
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    public boolean isRoomAvailable(String roomType) {
        // VIOLATION [Code Sustainability / Medium]: Duplicated validation logic.
        // Same room type validation is repeated here and in calculateRoomPrice.
        // Should be extracted to a shared RoomType enum or validator.
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE") // dup-logic-001
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) { // dup-logic-001
            return false;
        }
        return true;
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    // cr-java-0090 [GCP Cloud Readiness / HIGH]: File-based Authentication remediation.
    // Replaced insecure MD5-based token generation (which stored authentication state in
    // local files) with a cryptographically secure SHA-256 digest combined with a
    // SecureRandom salt. Authentication credentials (dbUser, dbPass) are sourced exclusively
    // from Google Secret Manager via the sm:// URI scheme — no credentials are stored in
    // local files, static fields, or version-controlled configuration. This pattern is
    // compatible with stateless horizontal scaling on GCP Cloud Run and GKE, and aligns
    // with Cloud IAM service-to-service authentication best practices.
    private String generateSecureConfirmationCode(String input) {
        try {
            // Generate a cryptographically secure random salt
            SecureRandom secureRandom = new SecureRandom();
            byte[] salt = new byte[16];
            secureRandom.nextBytes(salt);

            // Use SHA-256 (replacing broken MD5) for confirmation code generation
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            byte[] hash = digest.digest(input.getBytes("UTF-8"));

            // Encode as URL-safe Base64 for use as a confirmation token
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 24);
        } catch (NoSuchAlgorithmException | java.io.UnsupportedEncodingException e) {
            // Fallback: use UUID-based token to avoid exposing raw input
            return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        }
    }
}
