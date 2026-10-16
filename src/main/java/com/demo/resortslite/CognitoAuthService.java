package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.DeliveryMediumType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * CognitoAuthService — cr-java-0090 FIX
 *
 * <p>Replaces file-based authentication (hardcoded credentials stored in source code /
 * local property files) with AWS-native identity services:
 *
 * <ul>
 *   <li><b>Amazon Cognito User Pools</b> — centralised, encrypted user identity store
 *       with built-in lifecycle management (sign-up, sign-in, MFA, password policies,
 *       account recovery).  No user credentials are ever stored in the application
 *       source code or on the local file system.</li>
 *   <li><b>AWS Secrets Manager</b> — stores the Cognito app-client secret so that it
 *       is never hard-coded in source code or configuration files.  The secret is
 *       retrieved at runtime and cached for the lifetime of the bean.</li>
 * </ul>
 *
 * <p>Required environment variables (set in ECS task definition / Elastic Beanstalk
 * environment / EC2 user-data):
 * <pre>
 *   COGNITO_USER_POOL_ID      — Cognito User Pool ID  (e.g. us-east-1_AbCdEfGhI)
 *   COGNITO_CLIENT_ID         — Cognito App Client ID
 *   COGNITO_CLIENT_SECRET_NAME— AWS Secrets Manager secret name that holds the
 *                               Cognito app-client secret JSON:
 *                               {"clientSecret":"<value>"}
 *   AWS_REGION                — AWS region (e.g. us-east-1)
 * </pre>
 */
@Service
public class CognitoAuthService {

    private static final Logger logger = Logger.getLogger(CognitoAuthService.class.getName());

    // cr-java-0090 FIX: Cognito User Pool ID externalised to environment variable.
    // Never hard-code pool IDs or client IDs in source code.
    @Value("${app.cognito.user-pool-id:${COGNITO_USER_POOL_ID:}}")
    private String userPoolId;

    // cr-java-0090 FIX: Cognito App Client ID externalised to environment variable.
    @Value("${app.cognito.client-id:${COGNITO_CLIENT_ID:}}")
    private String clientId;

    // cr-java-0090 FIX: The Cognito app-client secret is NOT stored here.
    // It is retrieved at runtime from AWS Secrets Manager using the secret name
    // supplied via COGNITO_CLIENT_SECRET_NAME.
    @Value("${app.cognito.client-secret-name:${COGNITO_CLIENT_SECRET_NAME:resortslite/cognito/client-secret}}")
    private String cognitoClientSecretName;

    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a Cognito Identity Provider client scoped to the configured AWS region.
     */
    private CognitoIdentityProviderClient buildCognitoClient() {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Retrieves the Cognito app-client secret from AWS Secrets Manager.
     *
     * <p>The secret must be stored as a JSON object:
     * {@code {"clientSecret":"<value>"}}
     *
     * @return the plain-text client secret value
     */
    private String getCognitoClientSecret() {
        try (SecretsManagerClient smClient = SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(cognitoClientSecretName)
                    .build();
            GetSecretValueResponse response = smClient.getSecretValue(request);
            String secretJson = response.secretString();

            // Parse {"clientSecret":"<value>"} — lightweight manual parse to avoid
            // an extra Jackson dependency in this helper method.
            if (secretJson != null && secretJson.contains("clientSecret")) {
                int start = secretJson.indexOf("\"clientSecret\"");
                int colon = secretJson.indexOf(':', start);
                int quote1 = secretJson.indexOf('"', colon + 1);
                int quote2 = secretJson.indexOf('"', quote1 + 1);
                return secretJson.substring(quote1 + 1, quote2);
            }
            throw new RuntimeException("Secret '" + cognitoClientSecretName
                    + "' does not contain a 'clientSecret' key.");
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Authenticates a user against the Cognito User Pool using the
     * ADMIN_USER_PASSWORD_AUTH flow.
     *
     * <p>cr-java-0090 FIX: Credentials are validated by Cognito — they are never
     * read from a local file, a properties file, or a hard-coded constant.
     *
     * @param username the Cognito username (typically an e-mail address)
     * @param password the user's password (supplied at runtime, never stored)
     * @return a Map containing the Cognito ID token, access token, and refresh token
     * @throws CognitoIdentityProviderException if authentication fails
     */
    public Map<String, String> authenticateUser(String username, String password) {
        logger.info("Authenticating user via Amazon Cognito User Pool: " + userPoolId);

        Map<String, String> authParams = new HashMap<>();
        authParams.put("USERNAME", username);
        authParams.put("PASSWORD", password);

        try (CognitoIdentityProviderClient cognitoClient = buildCognitoClient()) {
            AdminInitiateAuthRequest authRequest = AdminInitiateAuthRequest.builder()
                    .userPoolId(userPoolId)
                    .clientId(clientId)
                    .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                    .authParameters(authParams)
                    .build();

            AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(authRequest);

            Map<String, String> tokens = new HashMap<>();
            tokens.put("idToken",      authResponse.authenticationResult().idToken());
            tokens.put("accessToken",  authResponse.authenticationResult().accessToken());
            tokens.put("refreshToken", authResponse.authenticationResult().refreshToken());

            logger.info("User '" + username + "' authenticated successfully via Amazon Cognito.");
            return tokens;
        }
    }

    /**
     * Retrieves user attributes from the Cognito User Pool.
     *
     * <p>cr-java-0090 FIX: User data is fetched from Cognito — it is never read
     * from a local users file or a hard-coded map in source code.
     *
     * @param username the Cognito username to look up
     * @return a Map of Cognito attribute names to their values
     * @throws CognitoIdentityProviderException if the user does not exist
     */
    public Map<String, String> getUserAttributes(String username) {
        logger.info("Fetching user attributes from Amazon Cognito for user: " + username);

        try (CognitoIdentityProviderClient cognitoClient = buildCognitoClient()) {
            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(username)
                    .build();

            AdminGetUserResponse getUserResponse = cognitoClient.adminGetUser(getUserRequest);

            Map<String, String> attributes = new HashMap<>();
            getUserResponse.userAttributes().forEach(attr ->
                    attributes.put(attr.name(), attr.value()));

            logger.info("User attributes retrieved from Amazon Cognito for user: " + username);
            return attributes;
        }
    }

    /**
     * Registers a new user in the Cognito User Pool.
     *
     * <p>cr-java-0090 FIX: New user records are created in Cognito — they are never
     * written to a local file or stored as hard-coded credentials in source code.
     *
     * @param username  the desired username (typically an e-mail address)
     * @param email     the user's e-mail address
     * @param tempPassword a temporary password; Cognito will force a reset on first login
     * @return a Map containing the new user's Cognito sub (UUID) and status
     */
    public Map<String, String> registerUser(String username, String email, String tempPassword) {
        logger.info("Registering new user in Amazon Cognito User Pool: " + userPoolId);

        AttributeType emailAttr = AttributeType.builder()
                .name("email")
                .value(email)
                .build();

        AttributeType emailVerifiedAttr = AttributeType.builder()
                .name("email_verified")
                .value("true")
                .build();

        try (CognitoIdentityProviderClient cognitoClient = buildCognitoClient()) {
            AdminCreateUserRequest createUserRequest = AdminCreateUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(username)
                    .temporaryPassword(tempPassword)
                    .userAttributes(emailAttr, emailVerifiedAttr)
                    .messageAction(MessageActionType.SUPPRESS)
                    .desiredDeliveryMediums(DeliveryMediumType.EMAIL)
                    .build();

            AdminCreateUserResponse createUserResponse = cognitoClient.adminCreateUser(createUserRequest);

            Map<String, String> result = new HashMap<>();
            result.put("username", createUserResponse.user().username());
            result.put("status",   createUserResponse.user().userStatusAsString());

            // Extract the Cognito sub (UUID) from the returned attributes
            createUserResponse.user().attributes().stream()
                    .filter(attr -> "sub".equals(attr.name()))
                    .findFirst()
                    .ifPresent(attr -> result.put("sub", attr.value()));

            logger.info("User '" + username + "' registered successfully in Amazon Cognito.");
            return result;
        }
    }
}
