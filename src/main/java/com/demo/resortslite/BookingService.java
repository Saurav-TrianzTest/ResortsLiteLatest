package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIX: Hard-coded database credentials replaced with AWS Secrets Manager.
    // DB_USER and DB_PASS are no longer stored in source code. Credentials are retrieved
    // at runtime from AWS Secrets Manager using the secret name configured via the
    // DB_SECRET_NAME environment variable (default: resortslite/db/credentials).
    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021 (separate rule)

    @Value("${aws.secretsmanager.db.secret-name:resortslite/db/credentials}")
    private String dbSecretName;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    // cr-java-0090 FIX: Amazon Cognito User Pool configuration.
    // User identity and authentication are now managed by Amazon Cognito instead of
    // local file-based credential storage. The User Pool ID and App Client ID are
    // injected via environment variables (COGNITO_USER_POOL_ID, COGNITO_APP_CLIENT_ID)
    // so no authentication configuration is hard-coded in source code.
    @Value("${aws.cognito.user-pool-id:${COGNITO_USER_POOL_ID:}}")
    private String cognitoUserPoolId;

    @Value("${aws.cognito.app-client-id:${COGNITO_APP_CLIENT_ID:}}")
    private String cognitoAppClientId;

    /**
     * cr-java-0090 FIX: Authenticates a user via Amazon Cognito User Pool.
     *
     * Replaces any file-based authentication (reading credentials from local files,
     * properties files, or hardcoded credential maps) with a cloud-native identity
     * check against Amazon Cognito. Cognito provides centralized, encrypted, and
     * auditable authentication with built-in user lifecycle management, MFA support,
     * and seamless horizontal scaling across all application instances.
     *
     * The Cognito User Pool ID and App Client ID are sourced from environment variables
     * (COGNITO_USER_POOL_ID, COGNITO_APP_CLIENT_ID) — never hardcoded in source code.
     *
     * @param username the Cognito username (typically an email address)
     * @param password the user's password
     * @return a map containing the Cognito authentication tokens (IdToken, AccessToken,
     *         RefreshToken) on success, or an error entry on failure
     */
    public Map<String, Object> authenticateUser(String username, String password) {
        Map<String, Object> authResult = new HashMap<>();
        try (CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            Map<String, String> authParams = new HashMap<>();
            authParams.put("USERNAME", username);
            authParams.put("PASSWORD", password);

            AdminInitiateAuthRequest authRequest = AdminInitiateAuthRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .clientId(cognitoAppClientId)
                    .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                    .authParameters(authParams)
                    .build();

            AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(authRequest);

            authResult.put("authenticated", true);
            authResult.put("idToken",
                    authResponse.authenticationResult().idToken());
            authResult.put("accessToken",
                    authResponse.authenticationResult().accessToken());
            authResult.put("refreshToken",
                    authResponse.authenticationResult().refreshToken());
            authResult.put("expiresIn",
                    authResponse.authenticationResult().expiresIn());

        } catch (NotAuthorizedException e) {
            authResult.put("authenticated", false);
            authResult.put("error", "Invalid username or password.");
        } catch (UserNotFoundException e) {
            authResult.put("authenticated", false);
            authResult.put("error", "User not found in Cognito User Pool.");
        } catch (Exception e) {
            authResult.put("authenticated", false);
            authResult.put("error", "Authentication service error: " + e.getMessage());
        }
        return authResult;
    }

    /**
     * cr-java-0090 FIX: Retrieves user profile attributes from Amazon Cognito User Pool.
     *
     * Replaces any file-based user data storage (reading user records from local files
     * or hardcoded user maps) with a lookup against the Cognito User Pool. User data
     * is centrally managed, encrypted at rest, and auditable via AWS CloudTrail.
     *
     * @param username the Cognito username to look up
     * @return a map of Cognito user attributes (email, name, custom attributes, etc.)
     */
    public Map<String, Object> getUserProfile(String username) {
        Map<String, Object> userProfile = new HashMap<>();
        try (CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(username)
                    .build();

            AdminGetUserResponse getUserResponse = cognitoClient.adminGetUser(getUserRequest);

            userProfile.put("username", getUserResponse.username());
            userProfile.put("userStatus", getUserResponse.userStatusAsString());
            userProfile.put("enabled", getUserResponse.enabled());

            // Map Cognito user attributes (email, name, phone_number, custom:*, etc.)
            getUserResponse.userAttributes().forEach(attr ->
                    userProfile.put(attr.name(), attr.value()));

        } catch (UserNotFoundException e) {
            userProfile.put("error", "User not found in Cognito User Pool: " + username);
        } catch (Exception e) {
            userProfile.put("error", "Failed to retrieve user profile from Cognito: " + e.getMessage());
        }
        return userProfile;
    }

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * The secret is expected to be a JSON object with "username" and "password" keys.
     * Example secret value: {"username":"admin","password":"Resort$Pass#2019!"}
     *
     * @param key "username" or "password"
     * @return the credential value for the requested key
     */
    private String getDbCredential(String key) {
        try (SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build()) {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();
            GetSecretValueResponse response = client.getSecretValue(request);
            String secretJson = response.secretString();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(secretJson);
            return node.get(key).asText();
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve DB credential '" + key
                    + "' from AWS Secrets Manager (secret: " + dbSecretName + ")", e);
        }
    }

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
