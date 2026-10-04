package com.vcut.api.shared.errors;

import java.math.BigDecimal;
import org.springframework.http.HttpStatus;

public class QuotaExceededException extends ApiException {

  private final BigDecimal requestedMinutes;
  private final BigDecimal availableMinutes;
  private final BigDecimal limitMinutes;

  public QuotaExceededException(
      BigDecimal requestedMinutes, BigDecimal availableMinutes, BigDecimal limitMinutes) {
    super(
        "VIDEO_QUOTA_EXCEEDED",
        "Quota insuficiente: solicitado "
            + requestedMinutes.toPlainString()
            + " min, disponível "
            + availableMinutes.toPlainString()
            + " min de um limite de "
            + limitMinutes.toPlainString()
            + " min.",
        HttpStatus.TOO_MANY_REQUESTS);
    this.requestedMinutes = requestedMinutes;
    this.availableMinutes = availableMinutes;
    this.limitMinutes = limitMinutes;
  }

  public BigDecimal requestedMinutes() {
    return requestedMinutes;
  }

  public BigDecimal availableMinutes() {
    return availableMinutes;
  }

  public BigDecimal limitMinutes() {
    return limitMinutes;
  }
}
