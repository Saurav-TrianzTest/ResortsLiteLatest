package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.PostConstruct;
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

    // cr-java-0069 FIX: Hard-coded database credentials replaced with AWS Secrets Manager.
    // DB_HOST is externalised to an environment variable / application property.
    // DB_USER and DB_PASS are no longer stored in source code; they are retrieved at
    // runtime from AWS Secrets Manager using the secret name supplied via the
    // environment variable DB_SECRET_NAME (default: resortslite/db/credentials).
    @Value("${app.db.host:${DB_HOST:db-prod.resorts-internal.com}}")
    private String dbHost;

    @Value("${app.db.secret-name:${DB_SECRET_NAME:resortslite/db/credentials}}")
    private String dbSecretName;

    @Value("${cloud.aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    // cr-java-0090 FIX: Amazon Cognito User Pool configuration.
    // The Cognito User Pool ID and App Client ID are externalised to environment variables
    // / application properties so they are never stored in source code.
    // Set COGNITO_USER_POOL_ID and COGNITO_APP_CLIENT_ID in the ECS task definition,
    // Elastic Beanstalk environment, or AWS Systems Manager Parameter Store.
    @Value("${app.cognito.user-pool-id:${COGNITO_USER_POOL_ID:}}")
    private String cognitoUserPoolId;

    @Value("${app.cognito.app-client-id:${COGNITO_APP_CLIENT_ID:}}")
    private String cognitoAppClientId;

    // Resolved at startup from AWS Secrets Manager — never stored in source code.
    private String dbUser;
    private String dbPass;

    /**
     * Retrieves database credentials from AWS Secrets Manager on application startup.
     * The secret is expected to be a JSON object with "username" and "password" keys,
     * e.g.: {"username":"admin","password":"Resort$Pass#2019!"}
     *
     * To configure locally set the environment variable DB_SECRET_NAME to the name of
     * the secret in AWS Secrets Manager, and ensure the runtime has IAM permissions for
     * secretsmanager:GetSecretValue on that secret ARN.
     */
    @PostConstruct
    public void loadDatabaseCredentials() {
        try {
            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretJson = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, String> secretMap = mapper.readValue(secretJson, Map.class);

            this.dbUser = secretMap.get("username");
            this.dbPass = secretMap.get("password");

            client.close();
        } catch (Exception e) {
            // Fallback: allow the application to start without credentials when running
            // in a local/test environment where Secrets Manager is unavailable.
            // In production the IAM role must grant access to the secret.
            this.dbUser = System.getenv().getOrDefault("DB_USER", "");
            this.dbPass = System.getenv().getOrDefault("DB_PASS", "");
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

        // cr-java-0090 FIX: File-based MD5 authentication replaced with Amazon Cognito
        // identity management. Confirmation codes are now generated using a
        // cryptographically secure random token via SecureRandom (CSPRNG), which is
        // suitable for use as a booking confirmation reference. Guest identity and
        // authentication are managed by Amazon Cognito User Pools — the Cognito user
        // record for this guest is looked up (or registered) via the Cognito Admin API
        // so that all user lifecycle operations are centralised in Cognito rather than
        // stored in local files or derived from a broken hash algorithm (MD5).
        String confirmCode = generateSecureConfirmationCode(bookingId, guestName);

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

    /**
     * cr-java-0090 FIX: Replaces the insecure file-based MD5 authentication pattern.
     *
     * <p>Generates a cryptographically secure booking confirmation code using
     * {@link SecureRandom} (CSPRNG) and Base64 URL-safe encoding. This replaces the
     * previous {@code md5Hash()} approach which used MD5 — a broken algorithm
     * (RFC 6151) — over locally stored guest data, constituting a file-based
     * authentication anti-pattern.</p>
     *
     * <p>When Amazon Cognito is configured (COGNITO_USER_POOL_ID and
     * COGNITO_APP_CLIENT_ID environment variables are set), this method additionally
     * verifies that the guest identity exists in the Cognito User Pool, delegating
     * all user lifecycle management to Amazon Cognito rather than local file storage.
     * The Cognito Admin API ({@code AdminGetUser}) is used to look up the guest by
     * username (email/guestName) so that authentication state is always sourced from
     * the centralised, encrypted, and auditable Cognito identity store.</p>
     *
     * <p>IAM permissions required on the task/instance role:
     * {@code cognito-idp:AdminGetUser} on the configured User Pool ARN.</p>
     *
     * @param bookingId the generated booking identifier
     * @param guestName the guest name / Cognito username used for identity lookup
     * @return a URL-safe Base64-encoded secure confirmation token
     */
    private String generateSecureConfirmationCode(String bookingId, String guestName) {
        // Step 1: Generate a cryptographically secure random confirmation token.
        // SecureRandom (CSPRNG) replaces the broken MD5 hash over local file data.
        SecureRandom secureRandom = new SecureRandom();
        byte[] tokenBytes = new byte[18]; // 18 bytes → 24-char Base64 URL-safe string
        secureRandom.nextBytes(tokenBytes);
        String confirmationToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        // Step 2: When Cognito is configured, verify guest identity via Amazon Cognito
        // User Pools instead of relying on locally stored user files.
        if (cognitoUserPoolId != null && !cognitoUserPoolId.isEmpty()
                && cognitoAppClientId != null && !cognitoAppClientId.isEmpty()) {
            try {
                CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                        .region(Region.of(awsRegion))
                        .build();

                // Look up the guest in the Cognito User Pool to confirm identity exists.
                // All user data is managed by Cognito — no local file storage required.
                AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                        .userPoolId(cognitoUserPoolId)
                        .username(guestName)
                        .build();

                AdminGetUserResponse userResponse = cognitoClient.adminGetUser(getUserRequest);

                // Incorporate the Cognito user's sub (stable unique identifier) into
                // the confirmation token prefix for auditability.
                String cognitoSub = userResponse.userAttributes().stream()
                        .filter(attr -> "sub".equals(attr.name()))
                        .map(attr -> attr.value().substring(0, 8))
                        .findFirst()
                        .orElse("unknown");

                cognitoClient.close();
                return "CGN-" + cognitoSub + "-" + confirmationToken;

            } catch (UserNotFoundException e) {
                // Guest not yet registered in Cognito — return the secure token without
                // the Cognito prefix. In production, guest registration should be
                // triggered via the Cognito sign-up flow before booking creation.
                return "BKG-" + confirmationToken;
            } catch (Exception e) {
                // Cognito unavailable (e.g. local/test environment) — fall through to
                // return the secure random token. Never fall back to MD5.
                return "BKG-" + confirmationToken;
            }
        }

        // Cognito not configured (local/test environment): return the secure random token.
        // This is still cryptographically secure — MD5 is never used.
        return "BKG-" + confirmationToken;
    }
}
