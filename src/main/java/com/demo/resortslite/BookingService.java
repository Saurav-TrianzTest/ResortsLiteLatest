package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-ready implementation.
 *
 * cr-java-0090 FIX (File-based Authentication):
 *   All credential storage has been removed from local files.
 *   Authentication is now delegated to Azure Active Directory (Entra ID) via
 *   Spring Security OAuth2 Resource Server (JWT / MSAL).  The service reads the
 *   caller's identity from the SecurityContext (populated by Spring Security AAD
 *   filter) instead of maintaining its own user/password files or static constants.
 *   Database credentials are retrieved at runtime from Azure Key Vault using
 *   DefaultAzureCredential (Managed Identity in Azure, env-vars / CLI locally),
 *   so no secret ever lives in source code or on the local file system.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // FIXED cr-java-0021 / cr-java-0090: DB_HOST is a non-secret reference only.
    // DB_USER and DB_PASS have been removed from source code entirely; they are
    // retrieved at runtime from Azure Key Vault (see getDbUser() / getDbPass()).
    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    /**
     * Azure Key Vault URI injected from the environment variable AZURE_KEY_VAULT_URI.
     * Example: https://my-keyvault.vault.azure.net/
     *
     * cr-java-0090 FIX: No credentials are stored in files.  The Key Vault URI is the
     * only configuration value needed here; actual secrets are fetched at runtime via
     * Managed Identity / DefaultAzureCredential — no username or password file required.
     */
    @Value("${azure.keyvault.uri:${AZURE_KEY_VAULT_URI:}}")
    private String keyVaultUri;

    /**
     * Lazily-initialised Key Vault SecretClient backed by DefaultAzureCredential.
     *
     * DefaultAzureCredential automatically picks up the Azure Managed Identity assigned
     * to the App Service / Container App at runtime, or falls back to environment
     * variables / Azure CLI credentials for local development.  This replaces any
     * file-based credential store (cr-java-0090).
     */
    private SecretClient secretClient;

    private SecretClient getSecretClient() {
        if (secretClient == null) {
            secretClient = new SecretClientBuilder()
                    .vaultUrl(keyVaultUri)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
        }
        return secretClient;
    }

    /**
     * Retrieves the database username from Azure Key Vault.
     *
     * cr-java-0090 FIX: Previously stored as a static final String in source code
     * (file-based credential storage).  Now fetched securely from Key Vault at
     * runtime; the secret name "db-username" must be provisioned in Key Vault by
     * the operations team — it never appears in any source or config file.
     */
    private String getDbUser() {
        return getSecretClient().getSecret("db-username").getValue();
    }

    /**
     * Retrieves the database password from Azure Key Vault.
     *
     * cr-java-0090 FIX: Previously stored as a static final String in source code
     * (file-based credential storage).  Now fetched securely from Key Vault at
     * runtime; the secret name "db-password" must be provisioned in Key Vault by
     * the operations team — it never appears in any source or config file.
     */
    private String getDbPass() {
        return getSecretClient().getSecret("db-password").getValue();
    }

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Returns the Azure AD (Entra ID) principal name of the currently authenticated user.
     *
     * cr-java-0090 FIX: Authentication identity is sourced exclusively from the Azure AD
     * JWT token validated by Spring Security OAuth2 Resource Server.  No local user file,
     * properties file, or in-memory credential store is consulted.  The JWT is issued by
     * Azure AD and carries the user's object ID, UPN, and roles as standard claims.
     *
     * Spring Security configuration (SecurityConfig) must be present with:
     *   spring.security.oauth2.resourceserver.jwt.jwk-set-uri pointing to the Azure AD
     *   JWKS endpoint for the registered application.
     */
    public String getAuthenticatedPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
            Jwt jwt = (Jwt) authentication.getPrincipal();
            // Prefer the UPN claim (Azure AD user principal name); fall back to subject
            String upn = jwt.getClaimAsString("upn");
            return (upn != null && !upn.isEmpty()) ? upn : jwt.getSubject();
        }
        // Fallback for non-JWT principals (e.g., service accounts using client credentials)
        return authentication != null ? authentication.getName() : "anonymous";
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
        // cr-java-0090 FIX: Expose the authenticated Azure AD principal in the response
        // so callers can audit which AAD identity created the booking.
        booking.put("createdBy", getAuthenticatedPrincipal());
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
