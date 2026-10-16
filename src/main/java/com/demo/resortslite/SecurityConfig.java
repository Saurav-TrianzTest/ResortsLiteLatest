package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * SecurityConfig — Azure Active Directory OAuth2 Resource Server configuration.
 *
 * cr-java-0090 FIX (File-based Authentication):
 *   Configures Spring Security to validate Azure AD (Entra ID) JWT Bearer tokens for
 *   all incoming requests.  This replaces the previous pattern of storing authentication
 *   credentials and security tokens in local files or static fields.
 *
 * Key security properties enforced:
 *   • All API endpoints require a valid Azure AD JWT Bearer token.
 *   • Session creation is STATELESS — no server-side HttpSession is used for auth state,
 *     which is required for horizontal scaling in cloud environments.
 *   • CSRF protection is disabled for stateless REST APIs (tokens provide CSRF protection).
 *   • The JWT is validated against Azure AD's JWKS endpoint configured in
 *     application.properties (spring.security.oauth2.resourceserver.jwt.*).
 *
 * Azure AD setup requirements:
 *   1. Register the application in Azure AD (App Registration).
 *   2. Set AZURE_AD_TENANT_ID and AZURE_AD_CLIENT_ID environment variables in
 *      Azure App Service / Container Apps.
 *   3. Clients must obtain Bearer tokens from:
 *      https://login.microsoftonline.com/{tenant-id}/oauth2/v2.0/token
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Configures the Spring Security filter chain for Azure AD JWT Bearer token validation.
     *
     * @param http the {@link HttpSecurity} builder
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception if configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF for stateless REST API — JWT Bearer tokens provide CSRF protection
            .csrf(csrf -> csrf.disable())

            // All requests must be authenticated with a valid Azure AD JWT Bearer token
            .authorizeHttpRequests(auth -> auth
                // Health check and actuator endpoints can be accessed without authentication
                .antMatchers("/actuator/health", "/actuator/info").permitAll()
                // All other endpoints require a valid Azure AD JWT
                .anyRequest().authenticated()
            )

            // Configure as OAuth2 Resource Server with JWT Bearer token validation
            // JWT issuer URI and JWKS URI are configured in application.properties:
            //   spring.security.oauth2.resourceserver.jwt.issuer-uri
            //   spring.security.oauth2.resourceserver.jwt.jwk-set-uri
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt()
            )

            // Stateless session management — no HttpSession for authentication state.
            // This is required for cloud-native horizontal scaling (cr-java-0065 alignment).
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            );

        return http.build();
    }
}
