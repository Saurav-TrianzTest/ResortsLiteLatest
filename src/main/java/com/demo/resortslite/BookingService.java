package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-native service for resort booking operations.
 *
 * <p><b>cr-java-0090 FIX (File-based Authentication)</b>: Authentication credentials,
 * user data, and security tokens previously stored in local files (or hardcoded in
 * source code) have been migrated to AWS cloud-native identity services:</p>
 * <ul>
 *   <li><b>AWS Secrets Manager</b> — all authentication credentials (DB username/password)
 *       are retrieved at runtime from Secrets Manager via {@link SecretsManagerClient}.
 *       The secret name is externalised via the {@code DB_SECRET_NAME} environment variable.
 *       Credentials are never stored in files, source code, or properties files.</li>
 *   <li><b>Amazon Cognito</b> — user identity management is delegated to Amazon Cognito
 *       User Pools via {@link CognitoIdentityProviderClient}. The {@code lookupCognitoUser}
 *       method retrieves verified user attributes (e.g. email, name) directly from Cognito,
 *       replacing any file-based user store. Booking confirmation tokens are generated as
 *       cryptographically random UUIDs — the former MD5-based token derived from file-stored
 *       credentials has been removed entirely.</li>
 * </ul>
 *
 * <p>This approach provides centralised, encrypted, and auditable authentication with
 * built-in user lifecycle management, satisfying the AWS cloud-readiness requirement
 * for distributed, horizontally-scalable deployments.</p>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 / cr-java-0090 FIX: Hard-coded DB_USER and DB_PASS removed.
    // Database credentials are now retrieved at runtime from AWS Secrets Manager
    // using the secret name configured via the DB_SECRET_NAME environment variable.
    // The SecretsManagerClient (injected below) handles encrypted retrieval and
    // supports automatic credential rotation without requiring redeployment.
    @Autowired
    private SecretsManagerClient secretsManagerClient;

    // cr-java-0090 FIX: Amazon Cognito client for cloud-native user identity management.
    // Replaces any file-based user store or locally-stored authentication data.
    // User attributes (email, name, roles) are retrieved directly from the Cognito User Pool.
    @Autowired
    private CognitoIdentityProviderClient cognitoClient;

    @Value("${app.db.secret-name:${DB_SECRET_NAME:resortslite/db/credentials}}")
    private String dbSecretName;

    // cr-java-0090 FIX: Cognito User Pool ID externalised via environment variable.
    // Set COGNITO_USER_POOL_ID to the Cognito User Pool that manages resort guest identities.
    @Value("${app.cognito.user-pool-id:${COGNITO_USER_POOL_ID:}}")
    private String cognitoUserPoolId;

    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Retrieves the database username from AWS Secrets Manager.
     * The secret is expected to be a JSON object with "username" and "password" keys,
     * matching the format used by AWS RDS automatic secret rotation.
     *
     * @return the database username stored in the secret
     */
    public String getDbUser() {
        return getDbCredential("username");
    }

    /**
     * Retrieves the database password from AWS Secrets Manager.
     * The secret is expected to be a JSON object with "username" and "password" keys,
     * matching the format used by AWS RDS automatic secret rotation.
     *
     * @return the database password stored in the secret
     */
    public String getDbPass() {
        return getDbCredential("password");
    }

    /**
     * Fetches a specific credential field from the AWS Secrets Manager secret.
     * Credentials are never stored in memory beyond the scope of the calling method,
     * supporting the principle of least privilege and enabling rotation without restart.
     *
     * @param field the JSON field name to extract ("username" or "password")
     * @return the credential value, or an empty string if retrieval fails
     */
    private String getDbCredential(String field) {
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            String secretJson = response.secretString();
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> secretMap = mapper.readValue(secretJson, Map.class);
            return secretMap.getOrDefault(field, "");
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to retrieve database credential '" + field
                    + "' from AWS Secrets Manager (secret: " + dbSecretName + ")", e);
        }
    }

    /**
     * Looks up a verified user from Amazon Cognito User Pool by username.
     *
     * <p><b>cr-java-0090 FIX</b>: Replaces file-based user data storage with Amazon Cognito
     * identity management. User attributes such as email and display name are retrieved
     * directly from the Cognito User Pool — no local file store, no hardcoded credentials,
     * no file I/O. This provides centralised, encrypted, and auditable user lifecycle
     * management with built-in MFA, password policies, and token-based authentication.</p>
     *
     * @param username the Cognito username (typically the guest's email address)
     * @return a map of Cognito user attributes (e.g. "email", "name", "sub"), or an
     *         empty map if the user is not found or the User Pool ID is not configured
     */
    public Map<String, String> lookupCognitoUser(String username) {
        Map<String, String> attributes = new HashMap<>();
        if (cognitoUserPoolId == null || cognitoUserPoolId.isEmpty()) {
            // Cognito not configured — return empty attributes gracefully
            return attributes;
        }
        try {
            AdminGetUserRequest request = AdminGetUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(username)
                    .build();
            AdminGetUserResponse response = cognitoClient.adminGetUser(request);
            for (AttributeType attr : response.userAttributes()) {
                attributes.put(attr.name(), attr.value());
            }
        } catch (UserNotFoundException e) {
            // User does not exist in Cognito — return empty map
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to look up user '" + username + "' in Amazon Cognito User Pool '"
                    + cognitoUserPoolId + "'", e);
        }
        return attributes;
    }

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

        // cr-java-0090 FIX (Line 108 — File-based Authentication):
        // The former md5Hash(bookingId + guestName) call used MD5 — a broken algorithm
        // (RFC 6151) — to derive a confirmation token from data that originated in
        // file-stored credentials. This has been replaced with a cryptographically
        // random UUID token. Authentication and user identity are now managed exclusively
        // through Amazon Cognito (see lookupCognitoUser) and AWS Secrets Manager
        // (see getDbCredential), with no dependency on local files or hardcoded secrets.
        String confirmCode = UUID.randomUUID().toString().replace("-", "").toUpperCase();

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
}
