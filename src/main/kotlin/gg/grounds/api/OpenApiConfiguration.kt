package gg.grounds.api

import jakarta.ws.rs.ApplicationPath
import jakarta.ws.rs.core.Application
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType
import org.eclipse.microprofile.openapi.annotations.info.Info
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme

@ApplicationPath("/")
@OpenAPIDefinition(
    info =
        Info(
            title = "Match API",
            version = "1.0.0",
            description =
                "The matchmaker: players queue per mode, the service forms MMR-based matches, " +
                    "allocates an Agones GameServer and hands the proxy somewhere to route " +
                    "them. Results reported here are what move the Weng-Lin ladder.\n\n" +
                    "There is no project or region anywhere in this API. service-match runs " +
                    "inside the project's own vCluster, which is the tenancy boundary — a " +
                    "caller cannot address another project because it cannot reach another " +
                    "project's matchmaker.",
        )
)
@SecurityScheme(
    securitySchemeName = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description = "Projected ServiceAccount token with the grounds-services audience.",
)
class OpenApiConfiguration : Application()
