package dev.splitledger.common;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description at /v3/api-docs, interactive UI at /swagger-ui.html. springdoc builds the
 * endpoints from the controllers; this adds the title and the bearer token scheme, so the UI's
 * "Authorize" button works with a token from POST /users.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "SplitLedger API",
                version = "0.1",
                description = "Shared expenses for a group of friends: bank statement import without duplicates, "
                        + "double-entry ledger with a verifiable hash chain, search, insights, an activity feed "
                        + "and settle-up with GiroCode. Amounts are integer cents; errors are RFC 9457 problem details."),
        security = @SecurityRequirement(name = "apiToken"))
@SecurityScheme(
        name = "apiToken",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        description = "A session token from POST /sessions (sign-in), or an API token returned once by POST /users, e.g. slt_...")
class OpenApiConfig {
}
