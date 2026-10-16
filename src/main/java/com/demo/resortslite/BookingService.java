package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import jakarta.annotation.PostConstruct;

/**
 * BookingService — cloud-ready implementation.
 *
 * cr-java-0090 FIX (File-based Authentication):
 *   Authentication credentials and security tokens are NO LONGER stored in local files
 *   or generated via weak local hashing (MD5).  Instead, the application delegates all
 *   identity and access management to Azure Active Directory (Entra ID) via Spring
 *   Security's OAuth2 / OIDC resource-server support:
 *
 *   • The caller's identity is validated by Spring Security before any service method
 *     is reached (JWT Bearer token issued by Azure AD is verified against the JWKS
 *     endpoint configured in application.properties).
 *   • The authenticated principal (Azure AD subject OID) is retrieved from the
 *     SecurityContext and embedded in booking records instead of a locally-computed
 *     MD5 hash, providing a tamper-proof, centrally-managed confirmation token.
 *   • No credentials, passwords, or security tokens are stored in local files,
 *     static fields, or source code.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Azure Key Vault integration — credentials are retrieved at runtime from Key Vault
    // using DefaultAzureCredential (supports Managed Identity, environment variables, etc.)
    // No credentials are stored in source code or version control.
    @Value("${azure.keyvault.uri}")
    private String keyVaultUri;

    @Value("${azure.keyvault.secret.db-host:db-host-secret}")
    private String dbHostSecretName;

    @Value("${azure.keyvault.secret.db-user:db-user-secret}")
    private String dbUserSecretName;

    @Value("${azure.keyvault.secret.db-pass:db-pass-secret}")
    private String dbPassSecretName;

    private String DB_HOST;
    private String DB_USER;
    private String DB_PASS;

    @PostConstruct
    public void loadSecretsFromKeyVault() {
        SecretClient secretClient = new SecretClientBuilder()
                .vaultUrl(keyVaultUri)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        this.DB_HOST = secretClient.getSecret(dbHostSecretName).getValue();
        this.DB_USER = secretClient.getSecret(dbUserSecretName).getValue();
        this.DB_PASS = secretClient.getSecret(dbPassSecretName).getValue();
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

        // cr-java-0090 FIX: Replace MD5-based local token generation with Azure AD identity.
        // The confirmation code is now derived from the Azure AD-authenticated principal's
        // subject OID (Object ID) — a tamper-proof, centrally-managed identity token issued
        // by Azure Active Directory.  This eliminates local file-based credential storage
        // and weak cryptographic hashing entirely.
        String confirmCode = getAzureAdConfirmationToken(bookingId);

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
     * cr-java-0090 FIX: Azure AD-backed confirmation token generation.
     *
     * Replaces the previous {@code md5Hash()} method that used the broken MD5 algorithm
     * to generate authentication/confirmation tokens from locally-stored credentials.
     *
     * The token is now composed of:
     *   - The Azure AD subject OID of the currently authenticated principal (from the
     *     JWT Bearer token validated by Spring Security OAuth2 Resource Server), and
     *   - The booking ID as a correlation suffix.
     *
     * This approach:
     *   • Eliminates local file-based credential storage (no passwords/tokens in files).
     *   • Eliminates weak MD5 hashing for security-sensitive tokens.
     *   • Ties every booking confirmation to a verified Azure AD identity.
     *   • Scales horizontally — no local state or file I/O required.
     *
     * @param bookingId the newly created booking identifier
     * @return a confirmation token derived from the Azure AD principal OID
     */
    private String getAzureAdConfirmationToken(String bookingId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
            Jwt jwt = (Jwt) authentication.getPrincipal();
            // Use the Azure AD Object ID (OID) claim as the identity anchor.
            // The OID is a stable, unique identifier for the user/service principal in Azure AD.
            String oid = jwt.getClaimAsString("oid");
            if (oid != null && !oid.isEmpty()) {
                return oid + "-" + bookingId;
            }
            // Fallback: use the JWT subject claim if OID is not present
            String subject = jwt.getSubject();
            if (subject != null && !subject.isEmpty()) {
                return subject + "-" + bookingId;
            }
        }
        // Fallback for non-authenticated contexts (e.g., internal/system calls):
        // generate a secure random UUID-based token rather than a weak MD5 hash.
        return UUID.randomUUID().toString() + "-" + bookingId;
    }

    /**
     * @deprecated cr-java-0090: MD5-based token generation has been replaced by
     *             {@link #getAzureAdConfirmationToken(String)}.  MD5 is a broken
     *             hash algorithm (RFC 6151) and MUST NOT be used for any
     *             security-related purpose.  This method is retained only for
     *             reference and will be removed in the next release.
     */
    @Deprecated
    @SuppressWarnings("unused")
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
