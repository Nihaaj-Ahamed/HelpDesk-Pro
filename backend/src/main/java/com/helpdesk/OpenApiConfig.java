package com.helpdesk;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfig {

  @Bean
  OpenAPI openAPI() {
    return new OpenAPI()
      .info(
        new Info()
          .title("HelpDesk Pro API")
          .version("1.0")
          .description(
            "Company onboarding and membership-authorized workspaces. Auth POST requests require the configured frontend Origin header."
          )
      )
      .components(
        new Components().addSecuritySchemes(
          "bearer",
          new SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
        )
      )
      .addSecurityItem(new SecurityRequirement().addList("bearer"));
  }
}
