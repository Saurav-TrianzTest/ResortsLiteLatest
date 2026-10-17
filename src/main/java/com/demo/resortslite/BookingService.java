package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069: DB_HOST externalised to environment variable / application property
    private static final String DB_HOST = System.getenv().getOrDefault("DB_HOST", "");

    // cr-java-0069: DB_USER and DB_PASS are no longer hard-coded.
    // They are retrieved at startup from Azure Key Vault using DefaultAzureCredential,
    // which supports Managed Identity, environment variables, and other credential chains
    // without embedding secrets in source code or container images.
    private String dbUser;
    private String dbPass;

    // cr-java-0021 / cr-java-0088: PAYMENT_API externalised to environment variable
    private static final String PAYMENT_API =
            System.getenv().getOrDefault("PAYMENT_API_URL", "");

    /**
     * Azure Key Vault URI is supplied via the environment variable AZURE_KEYVAULT_URI
     * (e.g. https://my-vault.vault.azure.net/).  The secret names follow the convention
     * used when the secrets were originally stored in Key Vault:
     *   db-username  →  DB_USER value
     *   db-password  →  DB_PASS value
     */
    @Value("${azure.keyvault.uri:#{environment['AZURE_KEYVAULT_URI']}}")
    private String keyVaultUri;

    @PostConstruct
    public void loadCredentialsFromKeyVault() {
        if (keyVaultUri == null || keyVaultUri.isBlank()) {
            throw new IllegalStateException(
                    "AZURE_KEYVAULT_URI environment variable is not set. " +
                    "Database credentials cannot be loaded from Azure Key Vault.");
        }

        SecretClient secretClient = new SecretClientBuilder()
                .vaultUrl(keyVaultUri)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();

        // Retrieve DB credentials from Azure Key Vault — no secrets in source code
        this.dbUser = secretClient.getSecret("db-username").getValue();
        this.dbPass = secretClient.getSecret("db-password").getValue();
    }

    // cr-java-0090 FIX: Replaced file-based authentication with Azure Active Directory
    // (Entra ID) identity resolution via Spring Security OAuth2 JWT context.
    //
    // Previously, user identity was resolved by reading credentials or tokens from local
    // files (e.g., a properties file or flat-file user store), which is incompatible with
    // stateless, horizontally-scaled cloud deployments — each instance would have its own
    // local file copy, leading to inconsistency and security risks.
    //
    // Now, the authenticated principal is obtained directly from the Spring Security
    // SecurityContext, which is populated by the Azure AD JWT bearer token validated on
    // every inbound request by SecurityConfig (AadResourceServerWebSecurityConfigurerAdapter).
    // No local file I/O is performed for authentication; identity is fully delegated to
    // Azure AD (Entra ID) as the centralized, scalable identity provider.

    /**
     * Resolves the currently authenticated user's name from the Azure AD JWT token
     * held in the Spring Security context.
     *
     * <p>The JWT is issued by Azure AD (Entra ID) and validated by the OAuth2 Resource
     * Server filter chain configured in {@link SecurityConfig}. The preferred_username
     * claim contains the user's UPN (e.g. user@tenant.onmicrosoft.com), which is used
     * as the canonical identity throughout the booking workflow.</p>
     *
     * @return the authenticated user's preferred username from the Azure AD JWT,
     *         or "anonymous" if no authentication context is present (e.g. health probes)
     */
    public String getAuthenticatedUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "anonymous";
        }
        Object principal = authentication.getPrincipal();
        // Azure AD JWT principal — extract preferred_username claim (UPN)
        if (principal instanceof Jwt) {
            Jwt jwt = (Jwt) principal;
            String preferredUsername = jwt.getClaimAsString("preferred_username");
            if (preferredUsername != null && !preferredUsername.isBlank()) {
                return preferredUsername;
            }
            // Fall back to 'name' claim (display name) if UPN is not present
            String name = jwt.getClaimAsString("name");
            if (name != null && !name.isBlank()) {
                return name;
            }
            // Last resort: use the subject (object ID)
            return jwt.getSubject();
        }
        return authentication.getName();
    }

    /**
     * Returns the Azure AD app roles assigned to the currently authenticated user.
     *
     * <p>Roles are mapped from the {@code roles} claim in the Azure AD JWT and surfaced
     * as {@code APPROLE_<roleName>} granted authorities by the Azure Spring Boot Starter.
     * This replaces any file-based role/permission lookups that were previously performed
     * against a local user store.</p>
     *
     * @return a comma-separated string of the user's Azure AD app roles, or "none"
     */
    public String getAuthenticatedUserRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "none";
        }
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(", "));
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

        // cr-java-0090: Enrich booking record with the Azure AD-authenticated caller identity.
        // The authenticated username is resolved from the JWT bearer token in the Security
        // context — no local file lookup is performed.
        String authenticatedUser = getAuthenticatedUsername();

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", DB_HOST);
        booking.put("createdBy", authenticatedUser);  // cr-java-0090: Azure AD identity
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
