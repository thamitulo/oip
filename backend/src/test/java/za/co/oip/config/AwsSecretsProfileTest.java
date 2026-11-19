package za.co.oip.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import za.co.oip.OipBackendApplication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The "aws" profile must load secrets from AWS Secrets Manager before startup and refuse to start
 * when it can't. No AWS account needed: the endpoint points at a closed local port.
 */
class AwsSecretsProfileTest {

    @Test
    void awsProfileFailsFastWhenSecretsManagerIsUnreachable() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(OipBackendApplication.class)
            .web(WebApplicationType.NONE)
            .profiles("aws")
            .properties(
                "OIP_AWS_SECRET_ID=oip/test",
                "spring.cloud.aws.region.static=af-south-1",
                "spring.cloud.aws.credentials.access-key=test",
                "spring.cloud.aws.credentials.secret-key=test",
                "spring.cloud.aws.secretsmanager.endpoint=http://127.0.0.1:1");

        assertThatThrownBy(() -> app.run().close())
            .hasStackTraceContaining("AwsSecretsManagerPropertySourceNotFoundException");
    }
}
