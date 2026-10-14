package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * SecurityConfig — Azure Active Directory (Entra ID) Spring Security configuration.
 *
 * cr-java-0090 FIX (File-based Authentication):
 *   This class replaces any file-based or in-memory credential store with Azure AD
 *   JWT-based authentication using Spring Security OAuth2 Resource Server.
 *
 *   How it works:
 *   1. Every inbound HTTP request must carry a Bearer token in the Authorization header.
 *   2. Spring Security fetches the Azure AD JWKS (JSON Web Key Set) from the URI
 *      configured in application.properties
 *      (spring.security.oauth2.resourceserver.jwt.jwk-set-uri) and validates the
 *      token's signature, issuer, audience, and expiry.
 *   3. On successful validation the JWT claims are stored in the SecurityContext as a
 *      JwtAuthenticationToken; BookingService.getAuthenticatedPrincipal() reads the
 *      "upn" claim to identify the caller.
 *   4. No local user file, properties-file password, or static credential constant is
 *      consulted at any point — authentication is fully delegated to Azure AD.
 *
 *   Required environment variables / Azure App Service application settings:
 *     AZURE_AD_TENANT_ID  — Azure AD tenant GUID
 *     AZURE_AD_CLIENT_ID  — App registration client / application ID
 *
 *   These are referenced in application.properties via ${AZURE_AD_TENANT_ID} and
 *   ${AZURE_AD_CLIENT_ID} — they must never be hard-coded in source or config files.
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    /**
     * Configures the Spring Security filter chain to:
     * - Require JWT Bearer authentication for all API endpoints.
     * - Use Azure AD as the OAuth2 Resource Server (JWT issuer / JWKS URI set in
     *   application.properties).
     * - Enforce stateless session management (no server-side HttpSession for auth state),
     *   which is required for horizontal scaling in Azure App Service / Container Apps.
     * - Permit unauthenticated access to the Spring Boot Actuator health endpoint so
     *   Azure load-balancer health probes succeed without a token.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF — not needed for stateless JWT APIs
            .csrf().disable()

            // Stateless session: no HttpSession is created or used for security state.
            // Authentication state lives entirely in the validated JWT on each request.
            .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and()

            // Authorization rules
            .authorizeRequests()
                // Allow health-check endpoint without authentication (Azure load-balancer probes)
                .antMatchers("/actuator/health").permitAll()
                // All other endpoints require a valid Azure AD JWT
                .anyRequest().authenticated()
            .and()

            // Configure as OAuth2 Resource Server — Spring Security will validate the
            // Bearer JWT against the Azure AD JWKS URI defined in application.properties.
            // spring.security.oauth2.resourceserver.jwt.jwk-set-uri and
            // spring.security.oauth2.resourceserver.jwt.issuer-uri are read automatically.
            .oauth2ResourceServer()
                .jwt();

        return http.build();
    }
}
