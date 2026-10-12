package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import javax.annotation.PostConstruct;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-ready implementation.
 *
 * cr-java-0090 FIX (File-based Authentication):
 *   Authentication credentials and user identity are no longer stored in or read from
 *   local files or hard-coded constants.  Two AWS-managed services replace the previous
 *   file-based approach:
 *
 *   1. AWS Secrets Manager  — holds all sensitive credentials (DB username/password,
 *      Cognito app-client secret) in an encrypted, auditable secret store.  The secret
 *      is fetched once at startup via {@link #loadCredentialsFromSecretsManager()} and
 *      never written to disk.
 *
 *   2. Amazon Cognito User Pool — acts as the authoritative identity provider for all
 *      application users.  {@link #authenticateUser(String, String)} delegates credential
 *      validation to Cognito's AdminInitiateAuth API (USER_PASSWORD_AUTH flow) and returns
 *      the Cognito-issued JWT tokens (IdToken, AccessToken, RefreshToken) to the caller.
 *      {@link #getUserDetails(String)} retrieves user attributes directly from the Cognito
 *      User Pool, eliminating any need for a local user file or in-memory user map.
 *
 *   Configuration (supply via environment variables or AWS Parameter Store):
 *     AWS_COGNITO_USER_POOL_ID   — Cognito User Pool ID  (e.g. us-east-1_AbCdEfGhI)
 *     AWS_COGNITO_CLIENT_ID      — App client ID registered in the User Pool
 *     AWS_COGNITO_SECRET_NAME    — Secrets Manager secret that holds the app-client secret
 *                                  JSON: {"clientSecret":"..."}
 *     AWS_DB_SECRET_NAME         — Secrets Manager secret for DB credentials
 *                                  JSON: {"username":"...","password":"..."}
 *     AWS_REGION                 — AWS region for all SDK calls (default: us-east-1)
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIX: DB_HOST is externalised to an environment variable / application property.
    // Hard-coded hostname "db-prod.resorts-internal.com" has been removed.
    @Value("${DB_HOST:#{environment['DB_HOST'] ?: 'localhost'}}")
    private String dbHost;

    // cr-java-0069 FIX: DB_USER and DB_PASS are no longer hard-coded in source.
    // Credentials are retrieved at startup from AWS Secrets Manager using the secret name
    // supplied via the environment variable AWS_DB_SECRET_NAME (e.g. "resortslite/db/credentials").
    // The secret is expected to be a JSON object: {"username":"...","password":"..."}
    private String dbUser;
    private String dbPass;

    // Name of the AWS Secrets Manager secret that holds DB credentials.
    // Set the environment variable AWS_DB_SECRET_NAME before deploying.
    @Value("${AWS_DB_SECRET_NAME:resortslite/db/credentials}")
    private String dbSecretName;

    // AWS region used to reach Secrets Manager and Cognito.
    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    // -------------------------------------------------------------------------
    // cr-java-0090 FIX: Amazon Cognito configuration
    // -------------------------------------------------------------------------
    // Cognito User Pool ID — identifies the user directory that owns all application
    // user accounts.  Injected at runtime; never hard-coded.
    @Value("${AWS_COGNITO_USER_POOL_ID:}")
    private String cognitoUserPoolId;

    // Cognito App Client ID — the OAuth 2.0 client registered in the User Pool.
    @Value("${AWS_COGNITO_CLIENT_ID:}")
    private String cognitoClientId;

    // Cognito App Client Secret — fetched from Secrets Manager at startup (see below).
    // The secret JSON must contain the key "clientSecret".
    private String cognitoClientSecret;

    // Name of the Secrets Manager secret that holds the Cognito app-client secret.
    @Value("${AWS_COGNITO_SECRET_NAME:resortslite/cognito/clientSecret}")
    private String cognitoSecretName;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    @Value("${PAYMENT_API_URL:#{environment['PAYMENT_API_URL'] ?: 'http://localhost:9090/payments/charge'}}")
    private String paymentApi;

    // -------------------------------------------------------------------------
    // Startup: load all secrets from AWS Secrets Manager
    // -------------------------------------------------------------------------

    /**
     * cr-java-0090 FIX — Retrieves both database credentials and the Cognito app-client
     * secret from AWS Secrets Manager at application startup.
     *
     * <p>Using Secrets Manager ensures:
     * <ul>
     *   <li>No credentials are stored in source code, container images, or local files.</li>
     *   <li>Automatic rotation is supported without redeployment.</li>
     *   <li>Access is controlled via IAM policies attached to the task/pod role.</li>
     *   <li>All secret access is logged to AWS CloudTrail for audit purposes.</li>
     * </ul>
     *
     * <p>cr-java-0069 FIX — DB credentials (username / password) are also sourced here.
     */
    @PostConstruct
    public void loadCredentialsFromSecretsManager() {
        SecretsManagerClient client = null;
        try {
            client = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            ObjectMapper mapper = new ObjectMapper();

            // --- DB credentials (cr-java-0069) ---
            GetSecretValueRequest dbRequest = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();
            GetSecretValueResponse dbResponse = client.getSecretValue(dbRequest);
            JsonNode dbNode = mapper.readTree(dbResponse.secretString());
            // cr-java-0069 FIX (Line 22): DB_USER now sourced from AWS Secrets Manager
            this.dbUser = dbNode.get("username").asText();
            // cr-java-0069 FIX (Line 23): DB_PASS now sourced from AWS Secrets Manager
            this.dbPass = dbNode.get("password").asText();

            // --- Cognito app-client secret (cr-java-0090) ---
            // The Cognito client secret is required when computing the SECRET_HASH for
            // AdminInitiateAuth calls.  It is stored in a dedicated Secrets Manager secret
            // so it is never present in source code or configuration files.
            if (cognitoSecretName != null && !cognitoSecretName.isEmpty()) {
                GetSecretValueRequest cognitoRequest = GetSecretValueRequest.builder()
                        .secretId(cognitoSecretName)
                        .build();
                GetSecretValueResponse cognitoResponse = client.getSecretValue(cognitoRequest);
                JsonNode cognitoNode = mapper.readTree(cognitoResponse.secretString());
                this.cognitoClientSecret = cognitoNode.has("clientSecret")
                        ? cognitoNode.get("clientSecret").asText()
                        : "";
            }

        } catch (Exception e) {
            // Fallback: allow environment variables to supply credentials when running
            // locally or in environments where Secrets Manager is not reachable.
            // In production, the IAM role must grant secretsmanager:GetSecretValue.
            this.dbUser = System.getenv().getOrDefault("DB_USER", "");
            this.dbPass = System.getenv().getOrDefault("DB_PASS", "");
            this.cognitoClientSecret = System.getenv().getOrDefault("COGNITO_CLIENT_SECRET", "");
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    // -------------------------------------------------------------------------
    // cr-java-0090 FIX: Amazon Cognito — user authentication
    // -------------------------------------------------------------------------

    /**
     * Authenticates an application user against the Amazon Cognito User Pool.
     *
     * <p>This method replaces any previous file-based or in-memory credential lookup.
     * Credentials are validated entirely by Cognito; the application never stores or
     * compares passwords locally.  On success, Cognito returns signed JWT tokens that
     * the caller can use for subsequent authorised requests.
     *
     * <p>cr-java-0090 FIX: Authentication is delegated to Amazon Cognito (USER_PASSWORD_AUTH
     * flow via AdminInitiateAuth).  No user data or tokens are written to local files.
     *
     * @param username the Cognito username (typically the user's email address)
     * @param password the user's password — validated by Cognito, never stored locally
     * @return a map containing Cognito JWT tokens: idToken, accessToken, refreshToken;
     *         or an "error" key if authentication fails
     */
    public Map<String, Object> authenticateUser(String username, String password) {
        Map<String, Object> result = new HashMap<>();

        if (cognitoUserPoolId == null || cognitoUserPoolId.isEmpty()
                || cognitoClientId == null || cognitoClientId.isEmpty()) {
            result.put("error", "Cognito is not configured. "
                    + "Set AWS_COGNITO_USER_POOL_ID and AWS_COGNITO_CLIENT_ID.");
            return result;
        }

        try (CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            // Build the auth parameters map.
            // cr-java-0090 FIX: Credentials are passed directly to Cognito over TLS;
            // they are never written to a local file or stored in application memory
            // beyond the scope of this call.
            Map<String, String> authParams = new HashMap<>();
            authParams.put("USERNAME", username);
            authParams.put("PASSWORD", password);

            // Include SECRET_HASH when the app client has a client secret configured.
            if (cognitoClientSecret != null && !cognitoClientSecret.isEmpty()) {
                authParams.put("SECRET_HASH",
                        computeCognitoSecretHash(username, cognitoClientId, cognitoClientSecret));
            }

            AdminInitiateAuthRequest authRequest = AdminInitiateAuthRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .clientId(cognitoClientId)
                    .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                    .authParameters(authParams)
                    .build();

            AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(authRequest);

            // Return the Cognito-issued JWT tokens to the caller.
            // These tokens are short-lived and signed by Cognito; the application does
            // not persist them to disk or any local store.
            if (authResponse.authenticationResult() != null) {
                result.put("idToken",      authResponse.authenticationResult().idToken());
                result.put("accessToken",  authResponse.authenticationResult().accessToken());
                result.put("refreshToken", authResponse.authenticationResult().refreshToken());
                result.put("tokenType",    authResponse.authenticationResult().tokenType());
                result.put("expiresIn",    authResponse.authenticationResult().expiresIn());
            } else {
                // Cognito may return a challenge (e.g. NEW_PASSWORD_REQUIRED).
                result.put("challengeName", authResponse.challengeNameAsString());
                result.put("session",       authResponse.session());
            }

        } catch (NotAuthorizedException e) {
            result.put("error", "Authentication failed: invalid username or password.");
        } catch (UserNotFoundException e) {
            result.put("error", "Authentication failed: user not found.");
        } catch (Exception e) {
            result.put("error", "Authentication error: " + e.getMessage());
        }

        return result;
    }

    /**
     * Retrieves user attributes from the Amazon Cognito User Pool.
     *
     * <p>cr-java-0090 FIX: User data is fetched on-demand from Cognito rather than
     * read from a local file or in-memory map.  This ensures user lifecycle events
     * (creation, update, deletion) managed in Cognito are immediately reflected
     * without any local cache invalidation.
     *
     * @param username the Cognito username whose attributes should be retrieved
     * @return a map of Cognito user attributes (email, name, custom attributes, etc.)
     */
    public Map<String, Object> getUserDetails(String username) {
        Map<String, Object> userDetails = new HashMap<>();

        if (cognitoUserPoolId == null || cognitoUserPoolId.isEmpty()) {
            userDetails.put("error", "Cognito User Pool is not configured. "
                    + "Set AWS_COGNITO_USER_POOL_ID.");
            return userDetails;
        }

        try (CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(username)
                    .build();

            AdminGetUserResponse getUserResponse = cognitoClient.adminGetUser(getUserRequest);

            // Map Cognito user attributes into the result.
            // cr-java-0090 FIX: User data originates from Cognito, not a local file.
            userDetails.put("username",   getUserResponse.username());
            userDetails.put("userStatus", getUserResponse.userStatusAsString());
            userDetails.put("enabled",    getUserResponse.enabled());
            getUserResponse.userAttributes().forEach(attr ->
                    userDetails.put(attr.name(), attr.value()));

        } catch (UserNotFoundException e) {
            userDetails.put("error", "User not found: " + username);
        } catch (Exception e) {
            userDetails.put("error", "Failed to retrieve user details: " + e.getMessage());
        }

        return userDetails;
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

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

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

    /**
     * Computes the SECRET_HASH required by Cognito when an app client has a client secret.
     * The hash is Base64( HMAC-SHA256( clientSecret, username + clientId ) ).
     *
     * @param username   Cognito username
     * @param clientId   Cognito app client ID
     * @param clientSecret Cognito app client secret (from Secrets Manager)
     * @return Base64-encoded HMAC-SHA256 hash
     */
    private String computeCognitoSecretHash(String username, String clientId, String clientSecret) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKey =
                    new javax.crypto.spec.SecretKeySpec(
                            clientSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            "HmacSHA256");
            mac.init(secretKey);
            byte[] rawHmac = mac.doFinal(
                    (username + clientId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(rawHmac);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute Cognito SECRET_HASH", e);
        }
    }
}
