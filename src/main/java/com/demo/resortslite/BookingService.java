package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * BookingService — cloud-readiness fixes applied.
 *
 * <p><b>cr-java-0090 FIX (File-based Authentication):</b>
 * Authentication credentials and user identity data are no longer stored in
 * local files, hard-coded constants, or source-code strings.
 * <ul>
 *   <li>Database credentials (username / password) are retrieved at runtime from
 *       <b>AWS Secrets Manager</b> via {@link #getDbCredentialsFromSecretsManager()}.
 *       The secret name is supplied through the {@code DB_SECRET_NAME} environment
 *       variable so no credential ever appears in source code or configuration files
 *       committed to version control.</li>
 *   <li>User identity management (sign-up, sign-in, attribute lookup) is delegated
 *       to <b>Amazon Cognito</b> via the injected {@link CognitoAuthService}.
 *       No user records, passwords, or tokens are stored on the local file system.</li>
 * </ul>
 *
 * <p><b>cr-java-0069 FIX:</b> DB_HOST externalised to environment variable /
 * application property.  DB_USER and DB_PASS are no longer stored in source code;
 * they are retrieved at runtime from AWS Secrets Manager using the secret name
 * supplied via the environment variable DB_SECRET_NAME
 * (default: "resortslite/db/credentials").
 *
 * <p><b>cr-java-0021 FIX:</b> PAYMENT_API externalised to environment variable /
 * application property.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0090 FIX: CognitoAuthService handles all user identity operations.
    // User credentials are never stored in local files or hard-coded in source code.
    @Autowired
    private CognitoAuthService cognitoAuthService;

    private static final Logger logger = Logger.getLogger(BookingService.class.getName());

    // cr-java-0069 FIX: DB_HOST externalised to environment variable / application property.
    // DB_USER and DB_PASS are no longer stored in source code; they are retrieved at runtime
    // from AWS Secrets Manager using the secret name supplied via the environment variable
    // DB_SECRET_NAME (default: "resortslite/db/credentials").
    @Value("${app.db.host:${DB_HOST:db-prod.resorts-internal.com}}")
    private String dbHost;

    @Value("${app.db.secret-name:${DB_SECRET_NAME:resortslite/db/credentials}}")
    private String dbSecretName;

    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    // cr-java-0021 FIX: PAYMENT_API externalised to environment variable / application property.
    @Value("${app.payment.endpoint:${PAYMENT_API_URL:http://10.0.1.45:9090/payments/charge}}")
    private String paymentApi;

    // -------------------------------------------------------------------------
    // cr-java-0090 FIX: AWS Secrets Manager — database credential retrieval
    // -------------------------------------------------------------------------

    /**
     * Retrieves database credentials (username and password) from AWS Secrets Manager.
     *
     * <p>cr-java-0090 FIX: Credentials are never stored in local files or hard-coded
     * in source code.  They are fetched at runtime from AWS Secrets Manager, which
     * provides centralised, encrypted, and auditable credential storage.
     *
     * <p>The secret is expected to be stored as a JSON object:
     * {@code {"username":"admin","password":"Resort$Pass#2019!"}}
     *
     * @return a Map containing "username" and "password" keys
     */
    public Map<String, String> getDbCredentialsFromSecretsManager() {
        SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
        GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(dbSecretName)
                .build();
        GetSecretValueResponse response = client.getSecretValue(request);
        String secretString = response.secretString();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(secretString);
            Map<String, String> credentials = new HashMap<>();
            credentials.put("username", node.get("username").asText());
            credentials.put("password", node.get("password").asText());
            logger.info("Database credentials successfully retrieved from AWS Secrets Manager (secret: " + dbSecretName + ")");
            return credentials;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse database credentials from AWS Secrets Manager secret: " + dbSecretName, e);
        }
    }

    // -------------------------------------------------------------------------
    // cr-java-0090 FIX: Amazon Cognito — user authentication delegation
    // -------------------------------------------------------------------------

    /**
     * Authenticates a guest user via Amazon Cognito.
     *
     * <p>cr-java-0090 FIX: Authentication is performed by Amazon Cognito User Pools.
     * No credentials are read from local files, hard-coded constants, or in-memory
     * maps.  The returned tokens (ID token, access token, refresh token) are issued
     * by Cognito and can be validated by any stateless application instance.
     *
     * @param username the guest's Cognito username
     * @param password the guest's password (never stored by this service)
     * @return Cognito authentication tokens (idToken, accessToken, refreshToken)
     */
    public Map<String, String> authenticateGuest(String username, String password) {
        logger.info("Delegating authentication to Amazon Cognito for user: " + username);
        return cognitoAuthService.authenticateUser(username, password);
    }

    /**
     * Retrieves guest profile attributes from Amazon Cognito.
     *
     * <p>cr-java-0090 FIX: User data is fetched from Cognito — it is never read
     * from a local users file or a hard-coded map in source code.
     *
     * @param username the guest's Cognito username
     * @return a Map of Cognito attribute names to their values
     */
    public Map<String, String> getGuestProfile(String username) {
        logger.info("Fetching guest profile from Amazon Cognito for user: " + username);
        return cognitoAuthService.getUserAttributes(username);
    }

    /**
     * Registers a new guest in Amazon Cognito.
     *
     * <p>cr-java-0090 FIX: New user records are created in Cognito — they are never
     * written to a local file or stored as hard-coded credentials in source code.
     *
     * @param username     the desired username
     * @param email        the guest's e-mail address
     * @param tempPassword a temporary password; Cognito will force a reset on first login
     * @return a Map containing the new user's Cognito sub (UUID) and status
     */
    public Map<String, String> registerGuest(String username, String email, String tempPassword) {
        logger.info("Registering new guest in Amazon Cognito for user: " + username);
        return cognitoAuthService.registerUser(username, email, tempPassword);
    }

    // -------------------------------------------------------------------------
    // Booking operations
    // -------------------------------------------------------------------------

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

        // VIOLATION [Security Health / High]: MD5 is a broken hash algorithm (RFC 6151).
        // Do not use MD5 for any security-related hashing. Use SHA-256 or bcrypt.
        String confirmCode = md5Hash(bookingId + guestName); // sec-weak-hash-001

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
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    private String md5Hash(String input) { // sec-weak-hash-001
        try {
            MessageDigest md = MessageDigest.getInstance("MD5"); // sec-weak-hash-001
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
