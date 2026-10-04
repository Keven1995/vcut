package com.vcut.api.subscription.application;

import com.vcut.api.subscription.domain.PaymentCustomer;
import java.util.Optional;
import java.util.UUID;

public interface PaymentCustomerRepository {

  Optional<PaymentCustomer> findByUserAndProvider(UUID userId, String provider);

  PaymentCustomer saveOrUpdate(PaymentCustomer paymentCustomer);
}
