package com.vcut.api.video.infrastructure;

import com.vcut.api.video.application.ExternalVideoImporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ExternalVideoImportConfiguration {

  @Bean
  ExternalVideoImporter.ImportLimits externalVideoImportLimits(
      ExternalVideoImportProperties properties) {
    return properties.limits();
  }
}
