package com.vcut.api.security.ratelimit.infrastructure;

import com.vcut.api.security.audit.infrastructure.SecurityAuditProperties;
import com.vcut.api.security.audit.presentation.SecurityAuditInterceptor;
import com.vcut.api.security.ratelimit.presentation.RateLimitInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({RateLimitProperties.class, SecurityAuditProperties.class})
public class RateLimitWebConfiguration implements WebMvcConfigurer {

  private final RateLimitInterceptor rateLimitInterceptor;
  private final SecurityAuditInterceptor securityAuditInterceptor;

  public RateLimitWebConfiguration(
      RateLimitInterceptor rateLimitInterceptor,
      SecurityAuditInterceptor securityAuditInterceptor) {
    this.rateLimitInterceptor = rateLimitInterceptor;
    this.securityAuditInterceptor = securityAuditInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(securityAuditInterceptor).order(0);
    registry.addInterceptor(rateLimitInterceptor).order(1);
  }
}
