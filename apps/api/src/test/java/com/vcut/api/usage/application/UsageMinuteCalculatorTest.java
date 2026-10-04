package com.vcut.api.usage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class UsageMinuteCalculatorTest {

  @Test
  void roundsAnyPartialMinuteUpAndKeepsExactMinutesStable() {
    assertThat(UsageApplicationService.processingMinutesForSeconds(BigDecimal.ONE))
        .isEqualByComparingTo("1");
    assertThat(UsageApplicationService.processingMinutesForSeconds(BigDecimal.valueOf(60)))
        .isEqualByComparingTo("1");
    assertThat(UsageApplicationService.processingMinutesForSeconds(BigDecimal.valueOf(61)))
        .isEqualByComparingTo("2");
    assertThat(UsageApplicationService.processingMinutesForSeconds(BigDecimal.valueOf(7_200)))
        .isEqualByComparingTo("120");
  }

  @Test
  void rejectsMissingOrNonPositiveDurations() {
    assertThatThrownBy(() -> UsageApplicationService.processingMinutesForSeconds(null))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> UsageApplicationService.processingMinutesForSeconds(BigDecimal.ZERO))
        .isInstanceOf(RuntimeException.class);
  }
}
