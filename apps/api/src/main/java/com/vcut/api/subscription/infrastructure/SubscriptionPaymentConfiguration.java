package com.vcut.api.subscription.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({SandboxPaymentProperties.class, SubscriptionProperties.class})
public class SubscriptionPaymentConfiguration {}
