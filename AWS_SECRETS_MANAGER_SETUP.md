# AWS Secrets Manager Setup for ResortsLite

## Overview
This application has been migrated from hard-coded database credentials to AWS Secrets Manager for secure, cloud-native credential management.

## Fixed Issues
- **cr-java-0069**: Hard-coded Database Credentials - Lines 22-23 in BookingService.java
  - Removed hard-coded `DB_USER` and `DB_PASS` constants
  - Implemented AWS Secrets Manager integration via `AwsSecretsManagerConfig`
  - Added automatic credential retrieval with fallback to environment variables

## AWS Secrets Manager Configuration

### 1. Create Secret in AWS Secrets Manager

Use the AWS CLI or Console to create a secret with the following JSON structure:

```bash
aws secretsmanager create-secret \
    --name resortslite/database/credentials \
    --description "Database credentials for ResortsLite application" \
    --secret-string '{
        "dbHost": "db-prod.resorts-internal.com",
        "dbUser": "admin",
        "dbPassword": "YourSecurePasswordHere",
        "paymentApiUrl": "https://payment-api.resorts.com/payments/charge"
    }' \
    --region us-east-1
```

### 2. IAM Permissions

Ensure the application's IAM role (EC2 instance role, ECS task role, or Lambda execution role) has the following permissions:

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "secretsmanager:GetSecretValue",
                "secretsmanager:DescribeSecret"
            ],
            "Resource": "arn:aws:secretsmanager:us-east-1:ACCOUNT_ID:secret:resortslite/database/credentials-*"
        }
    ]
}
```

### 3. Environment Variables

Configure the following environment variables for your deployment:

```bash
# Required
AWS_REGION=us-east-1
AWS_SECRETS_MANAGER_SECRET_NAME=resortslite/database/credentials

# Optional fallbacks (used if Secrets Manager is unavailable)
DB_HOST=localhost
DB_USER=sa
DB_PASSWORD=
PAYMENT_API_URL=http://localhost:9090/payments/charge
```

### 4. Secret Rotation

AWS Secrets Manager supports automatic secret rotation. To enable:

```bash
aws secretsmanager rotate-secret \
    --secret-id resortslite/database/credentials \
    --rotation-lambda-arn arn:aws:lambda:us-east-1:ACCOUNT_ID:function:SecretsManagerRotation \
    --rotation-rules AutomaticallyAfterDays=30
```

The application will automatically retrieve updated credentials on the next secret fetch.

## Local Development

For local development without AWS Secrets Manager access:

1. Set environment variables:
   ```bash
   export DB_HOST=localhost
   export DB_USER=sa
   export DB_PASSWORD=
   export PAYMENT_API_URL=http://localhost:9090/payments/charge
   ```

2. The application will fall back to these environment variables if Secrets Manager is unavailable.

## Benefits

✅ **Security**: Credentials no longer exposed in source code or version control  
✅ **Rotation**: Supports automatic credential rotation without code changes  
✅ **Centralized**: Single source of truth for all sensitive configuration  
✅ **Audit**: AWS CloudTrail logs all secret access for compliance  
✅ **Encryption**: Secrets encrypted at rest using AWS KMS  
✅ **Cloud-Native**: Follows AWS best practices for credential management  

## Testing

To verify the integration:

1. Deploy the application to AWS (EC2, ECS, or Lambda)
2. Ensure IAM permissions are correctly configured
3. Check application logs for: "Successfully retrieved secrets from AWS Secrets Manager"
4. Test database connectivity and payment API calls

## Troubleshooting

**Error: "Unable to retrieve secrets from AWS Secrets Manager"**
- Verify IAM permissions include `secretsmanager:GetSecretValue`
- Check the secret name matches the configuration
- Ensure the AWS region is correct
- Verify network connectivity to Secrets Manager endpoint

**Fallback to environment variables**
- Application will log: "Falling back to environment variables or default configuration"
- Set environment variables as documented above
- This is expected behavior for local development

## Migration Notes

### Before (Hard-coded):
```java
private static final String DB_USER = "admin";
private static final String DB_PASS = "Resort$Pass#2019!";
```

### After (AWS Secrets Manager):
```java
@Autowired
private AwsSecretsManagerConfig secretsConfig;

String dbUser = secretsConfig.getDbUser();
String dbPassword = secretsConfig.getDbPassword();
```

## Additional Resources

- [AWS Secrets Manager Documentation](https://docs.aws.amazon.com/secretsmanager/)
- [AWS SDK for Java v2 - Secrets Manager](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/secretsmanager/package-summary.html)
- [Best Practices for Secrets Management](https://docs.aws.amazon.com/secretsmanager/latest/userguide/best-practices.html)
