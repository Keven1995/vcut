package com.vcut.api.security.ratelimit.application;

import com.vcut.api.security.ratelimit.domain.RateLimitSubjectScope;

public interface RateLimitSubjectHasher {

  String hash(RateLimitSubjectScope scope, String subject);
}
