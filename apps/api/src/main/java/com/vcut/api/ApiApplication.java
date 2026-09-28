package com.vcut.api;

import com.vcut.api.shared.correlation.CorrelationIdFilter;
import com.vcut.api.shared.observability.RequestLoggingFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiApplication {

  @Bean
  FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
    FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(new CorrelationIdFilter());
    registration.setOrder(1);
    return registration;
  }

  @Bean
  FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilterRegistration() {
    FilterRegistrationBean<RequestLoggingFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(new RequestLoggingFilter());
    registration.setOrder(2);
    return registration;
  }

  public static void main(String[] args) {
    SpringApplication.run(ApiApplication.class, args);
  }
}
