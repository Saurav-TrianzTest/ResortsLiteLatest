package com.demo.resortslite;

// cr-java-0090 FIX: Azure Active Directory (Entra ID) Security Configuration.
// Replaces file-based authentication (local credential files / hardcoded user stores)
// with Azure AD OAuth2 / OIDC using Spring Security and the Azure Spring Boot Starter.
//
// Authentication flow:
//   1. Clients obtain a JWT bearer token from Azure AD (Entra ID) via MSAL or OAuth2 code flow.
//   2. Every API request must include the token in the Authorization header.
//   3. Spring Security validates the JWT signature against Azure AD's JWKS endpoint,
//      checks the audience (app registration client ID), and enforces role-based access.
//   4. No credentials are stored locally — identity is fully delegated to Azure AD.

import com.azure.spring.cloud.autoconfigure.aad.AadResourceServerWebSecurityConfigurerAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Azure AD (Entra ID) security configuration for the ResortsLite application.
 *
 * <p>This class configures the application as an OAuth2 Resource Server that validates
 * Azure AD-issued JWT bearer tokens. It replaces the previous file-based authentication
 * pattern (cr-java-0090) where user credentials or session tokens were stored in local
 * files, which is incompatible with stateless, horizontally-scaled cloud deployments.</p>
 *
 * <p>Required Azure AD application properties (set via environment variables or
 * Azure App Configuration):</p>
 * <ul>
 *   <li>{@code AZURE_AD_TENANT_ID}   — Azure AD tenant (directory) ID</li>
 *   <li>{@code AZURE_AD_CLIENT_ID}   — App registration (client) ID</li>
 *   <li>{@code AZURE_AD_CLIENT_SECRET} — App registration client secret (stored in Key Vault)</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig extends AadResourceServerWebSecurityConfigurerAdapter {

    /**
     * Configures the HTTP security filter chain to require Azure AD JWT authentication
     * on all protected endpoints.
     *
     * <p>The {@link AadResourceServerWebSecurityConfigurerAdapter} base class wires in
     * the Azure AD JWT decoder that validates tokens against the tenant's JWKS endpoint,
     * verifies the audience claim matches the configured client ID, and maps Azure AD
     * app roles to Spring Security {@code GrantedAuthority} objects.</p>
     *
     * <p>Endpoint access rules:</p>
     * <ul>
     *   <li>{@code /actuator/health} — public (liveness/readiness probes in Azure Container Apps)</li>
     *   <li>{@code /api/bookings/**} — requires a valid Azure AD JWT bearer token</li>
     *   <li>All other requests — require authentication</li>
     * </ul>
     */
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        super.configure(http);
        http.authorizeRequests(requests ->
                requests
                        // Allow health/readiness probes without authentication
                        .antMatchers("/actuator/health", "/actuator/info").permitAll()
                        // Booking creation requires the 'Bookings.Write' Azure AD app role
                        .antMatchers("/api/bookings/create").hasAuthority("APPROLE_Bookings.Write")
                        // Report download requires the 'Reports.Read' Azure AD app role
                        .antMatchers("/api/bookings/report/**").hasAuthority("APPROLE_Reports.Read")
                        // All other API endpoints require any authenticated Azure AD identity
                        .antMatchers("/api/**").authenticated()
                        // Deny everything else by default
                        .anyRequest().authenticated()
        );
    }
}
