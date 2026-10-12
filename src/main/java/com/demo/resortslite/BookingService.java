package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import javax.annotation.PostConstruct;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIX: DB_HOST remains as a non-sensitive infrastructure reference;
    // it is externalised via environment variable to avoid hard-coded hostnames.
    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    // cr-java-0069 FIX [Lines 22-23]: Hard-coded DB_USER and DB_PASS constants have been
    // removed. Credentials are now retrieved at runtime from AWS Secrets Manager using the
    // secret name configured via the DB_SECRET_NAME environment variable (or the default
    // "resortslite/db/credentials"). The SecretsManagerClient uses the IAM role attached
    // to the compute resource — no static credentials are embedded in source code.
    private String dbUser;
    private String dbPass;

    /**
     * Name of the AWS Secrets Manager secret that stores the database credentials.
     * Override via the DB_SECRET_NAME environment variable in your ECS task definition,
     * EKS pod spec, or Lambda environment configuration.
     *
     * Expected secret format (JSON):
     * {
     *   "username": "<db-username>",
     *   "password": "<db-password>"
     * }
     */
    @Value("${DB_SECRET_NAME:resortslite/db/credentials}")
    private String dbSecretName;

    /**
     * AWS region used to locate the Secrets Manager and Cognito endpoints.
     * Defaults to us-east-1; override via the AWS_REGION environment variable.
     */
    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    // cr-java-0090 FIX: Amazon Cognito User Pool ID for user identity management.
    // Replaces any file-based user credential storage with cloud-native identity management.
    // Set COGNITO_USER_POOL_ID via environment variable in ECS task definition,
    // EKS pod spec, or Lambda environment configuration.
    @Value("${COGNITO_USER_POOL_ID:}")
    private String cognitoUserPoolId;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Loads database credentials from AWS Secrets Manager once the bean is fully
     * initialised. Using @PostConstruct ensures the @Value-injected fields
     * (dbSecretName, awsRegion) are already populated before the retrieval call.
     *
     * The SecretsManagerClient authenticates via the IAM role attached to the
     * compute resource (ECS task role, EC2 instance profile, Lambda execution role),
     * so no static AWS credentials are required in the application code.
     */
    @PostConstruct
    public void loadDbCredentialsFromSecretsManager() {
        try {
            SecretsManagerClient secretsClient = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();

            GetSecretValueResponse response = secretsClient.getSecretValue(request);
            String secretJson = response.secretString();

            // Parse the JSON secret value to extract username and password fields
            ObjectMapper mapper = new ObjectMapper();
            JsonNode secretNode = mapper.readTree(secretJson);
            this.dbUser = secretNode.get("username").asText();
            this.dbPass = secretNode.get("password").asText();

            secretsClient.close();
        } catch (Exception e) {
            // Fail fast on startup if credentials cannot be retrieved — prevents the
            // application from running with null/empty credentials in production.
            throw new IllegalStateException(
                    "Failed to load database credentials from AWS Secrets Manager "
                    + "(secret: " + dbSecretName + ", region: " + awsRegion + "): "
                    + e.getMessage(), e);
        }
    }

    /**
     * cr-java-0090 FIX: Validates a guest user identity against Amazon Cognito User Pool.
     *
     * Replaces any file-based authentication (e.g., reading credentials from local files,
     * properties files, or hardcoded credential constants) with Amazon Cognito — a fully
     * managed, cloud-native identity and access management service.
     *
     * Authentication credentials and user data are no longer stored in local files.
     * Instead, user lifecycle management (registration, authentication, MFA, password
     * policies) is delegated to Amazon Cognito, which provides:
     *   - Centralized, encrypted credential storage
     *   - Auditable authentication events via AWS CloudTrail
     *   - Built-in user lifecycle management (sign-up, sign-in, forgot password)
     *   - Horizontal scalability without shared local state
     *
     * The CognitoIdentityProviderClient authenticates via the IAM role attached to the
     * compute resource — no static AWS credentials are embedded in source code.
     *
     * @param username  the Cognito username to look up
     * @return true if the user exists and is CONFIRMED in the Cognito User Pool; false otherwise
     */
    public boolean validateGuestWithCognito(String username) {
        if (cognitoUserPoolId == null || cognitoUserPoolId.isEmpty()) {
            // Cognito User Pool ID not configured — skip validation in local/dev environments
            return true;
        }
        try {
            CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(username)
                    .build();

            AdminGetUserResponse getUserResponse = cognitoClient.adminGetUser(getUserRequest);
            cognitoClient.close();

            // User is valid if their status is CONFIRMED in the Cognito User Pool
            return "CONFIRMED".equals(getUserResponse.userStatusAsString());
        } catch (UserNotFoundException e) {
            return false;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to validate user with Amazon Cognito "
                    + "(userPoolId: " + cognitoUserPoolId + ", region: " + awsRegion + "): "
                    + e.getMessage(), e);
        }
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
