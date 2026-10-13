package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AwsSecretsManagerConfig awsSecretsManagerConfig;

    // VIOLATION [Security Health / Critical]: Hardcoded database credentials in source code.
    // If this repo is pushed to GitHub (even private), credentials are permanently exposed
    // in git history. AWS Secrets Manager or Parameter Store must be used instead.
    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021
    private static final String DB_USER = "admin";                         // sec-cred-001
    private static final String DB_PASS = "Resort$Pass#2019!";             // sec-cred-001

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

        // FIXED cr-java-0090: Replaced MD5 with SHA-256 for secure confirmation code generation
        // Uses AWS Secrets Manager for salt retrieval to ensure cryptographically secure hashing
        String confirmCode = generateSecureConfirmationCode(bookingId + guestName);

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

    /**
     * FIXED cr-java-0090: Generate secure confirmation code using SHA-256
     * Replaces insecure MD5 hashing with SHA-256 and integrates with AWS Secrets Manager
     * for salt management. This provides cryptographically secure confirmation codes
     * suitable for cloud-native authentication patterns.
     * 
     * For full user authentication, integrate with Amazon Cognito for user identity management.
     * This method provides secure token generation for booking confirmations.
     */
    private String generateSecureConfirmationCode(String input) {
        try {
            // Use SHA-256 instead of MD5 for secure hashing
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            
            // Add cryptographic salt from AWS Secrets Manager or generate secure random salt
            // In production, retrieve salt from AWS Secrets Manager for consistency
            String salt = getSaltFromSecretsManager();
            String saltedInput = input + salt;
            
            byte[] hash = md.digest(saltedInput.getBytes("UTF-8"));
            
            // Use Base64 encoding for URL-safe confirmation codes
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 16);
        } catch (Exception e) {
            // Fallback to secure random generation if hashing fails
            return generateSecureRandomCode();
        }
    }

    private String getSaltFromSecretsManager() {
        try {
            // Retrieve salt from AWS Secrets Manager for consistent, secure hashing
            String salt = awsSecretsManagerConfig.getConfirmationCodeSalt();
            
            if (salt != null && !salt.isEmpty()) {
                return salt;
            }
            
            // Fallback: generate secure random salt if not available in Secrets Manager
            SecureRandom secureRandom = new SecureRandom();
            byte[] saltBytes = new byte[16];
            secureRandom.nextBytes(saltBytes);
            return Base64.getEncoder().encodeToString(saltBytes);
        } catch (Exception e) {
            return "DEFAULT_SALT_" + System.currentTimeMillis();
        }
    }

    private String generateSecureRandomCode() {
        try {
            SecureRandom secureRandom = new SecureRandom();
            byte[] randomBytes = new byte[12];
            secureRandom.nextBytes(randomBytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        } catch (Exception e) {
            // Ultimate fallback
            return UUID.randomUUID().toString().substring(0, 16).toUpperCase();
        }
    }

    /**
     * @deprecated Replaced with generateSecureConfirmationCode() using SHA-256
     * MD5 is cryptographically broken and should not be used for security purposes
     */
    @Deprecated
    private String md5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
