package com.vcut.api.security.ratelimit.application;

import com.vcut.api.security.ratelimit.domain.RateLimitBucket;
import java.time.Instant;

public interface RateLimitRepository {

  RateLimitBucket lockOrCreate(RateLimitBucket initial);

  void update(RateLimitBucket bucket);

  int deleteUpdatedBefore(Instant cutoff);
}
