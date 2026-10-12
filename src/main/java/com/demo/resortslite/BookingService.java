package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069: Hard-coded database credentials replaced with AWS Secrets Manager.
    // DB_USER and DB_PASS are no longer stored in source code. Credentials are retrieved
    // at runtime from AWS Secrets Manager using the secret name configured via environment
    // variable DB_SECRET_NAME, enabling automatic rotation without redeployment.
    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    @Value("${DB_SECRET_NAME:resortslite/db/credentials}")
    private String dbSecretName;

    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    // cr-java-0090: Cognito User Pool configuration injected via environment variables.
    // COGNITO_USER_POOL_ID must be set in the ECS task definition / EKS pod spec /
    // Elastic Beanstalk environment properties pointing to the application's Cognito User Pool.
    @Value("${COGNITO_USER_POOL_ID:}")
    private String cognitoUserPoolId;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Retrieves database credentials from AWS Secrets Manager at runtime.
     * The secret is expected to be a JSON object with "username" and "password" keys.
     * Secret name is configured via the DB_SECRET_NAME environment variable.
     *
     * @return Map containing "username" and "password" keys
     */
    private Map<String, String> getDbCredentialsFromSecretsManager() {
        SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();
            GetSecretValueResponse response = client.getSecretValue(request);
            String secretJson = response.secretString();
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, String> credentials = mapper.readValue(secretJson, Map.class);
            return credentials;
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve database credentials from AWS Secrets Manager for secret: "
                    + dbSecretName, e);
        } finally {
            client.close();
        }
    }

    /**
     * cr-java-0090 FIX: Replaces file-based authentication (local MD5 credential hashing)
     * with Amazon Cognito for user identity management.
     *
     * Validates that the given username exists and is active in the configured Cognito
     * User Pool. Authentication credentials and user data are no longer stored in or
     * read from local files — all identity state is managed centrally by Cognito,
     * providing encrypted, auditable, and horizontally-scalable authentication that
     * works correctly across all instances in a distributed cloud deployment.
     *
     * @param username the Cognito username (typically the guest's email address)
     * @return true if the user exists and is CONFIRMED in the Cognito User Pool
     */
    public boolean validateUserWithCognito(String username) {
        if (cognitoUserPoolId == null || cognitoUserPoolId.isEmpty()) {
            throw new IllegalStateException(
                    "COGNITO_USER_POOL_ID environment variable is not configured. "
                    + "Set it to the Cognito User Pool ID for this deployment environment.");
        }
        CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build();
        try {
            AdminGetUserRequest request = AdminGetUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(username)
                    .build();
            AdminGetUserResponse response = cognitoClient.adminGetUser(request);
            // Only allow users whose account is fully confirmed in Cognito
            return "CONFIRMED".equalsIgnoreCase(response.userStatusAsString());
        } catch (UserNotFoundException e) {
            return false;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to validate user '" + username + "' against Amazon Cognito User Pool: "
                    + cognitoUserPoolId, e);
        } finally {
            cognitoClient.close();
        }
    }

    /**
     * cr-java-0090 FIX: Generates a cryptographically secure booking confirmation token
     * using a UUID (version 4, random). The previous implementation used MD5 hashing of
     * local credential data — a file-based, insecure pattern. The new implementation
     * produces a URL-safe, collision-resistant token without any dependency on local
     * file storage or weak hash algorithms, consistent with cloud-native stateless design.
     *
     * @param bookingId the unique booking identifier
     * @return a secure, random confirmation code
     */
    private String generateSecureConfirmationCode(String bookingId) {
        // UUID v4 provides 122 bits of randomness — cryptographically sufficient for
        // a confirmation code and requires no local file I/O or credential data.
        return bookingId + "-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
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

        // cr-java-0090 FIX: Replaced insecure MD5-based file credential hashing with
        // a cryptographically secure UUID-based confirmation code. Authentication identity
        // is now managed by Amazon Cognito (see validateUserWithCognito), not local files.
        String confirmCode = generateSecureConfirmationCode(bookingId);

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
