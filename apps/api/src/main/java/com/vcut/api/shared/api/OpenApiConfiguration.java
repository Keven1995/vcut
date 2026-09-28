package com.vcut.api.shared.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.math.BigDecimal;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info =
        @Info(
            title = "Vcut API",
            version = "0.1.0",
            description = "Contratos HTTP da plataforma de cortes inteligentes de vídeo."))
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    in = SecuritySchemeIn.HEADER)
public class OpenApiConfiguration {

  @Bean
  OpenAPI openAPI() {
    return new OpenAPI();
  }

  @Bean
  OpenApiCustomizer commonSchemasCustomizer() {
    return openAPI -> {
      if (openAPI.getComponents() == null) {
        openAPI.components(new Components());
      }
      openAPI
          .getComponents()
          .addSchemas("ErrorResponse", errorResponseSchema())
          .addSchemas("PageMetadata", pageMetadataSchema());
    };
  }

  private ObjectSchema errorResponseSchema() {
    ObjectSchema errorResponse = new ObjectSchema();
    errorResponse.addProperty("code", new StringSchema().example("RESOURCE_NOT_FOUND"));
    errorResponse.addProperty("message", new StringSchema().example("Recurso não encontrado."));
    errorResponse.addProperty("traceId", new StringSchema().format("uuid"));
    return errorResponse;
  }

  private ObjectSchema pageMetadataSchema() {
    ObjectSchema pageMetadata = new ObjectSchema();
    pageMetadata.addProperty("page", new IntegerSchema().minimum(BigDecimal.ZERO));
    pageMetadata.addProperty("size", new IntegerSchema().minimum(BigDecimal.ONE));
    pageMetadata.addProperty("totalElements", new IntegerSchema().minimum(BigDecimal.ZERO));
    pageMetadata.addProperty("totalPages", new IntegerSchema().minimum(BigDecimal.ZERO));
    return pageMetadata;
  }
}
