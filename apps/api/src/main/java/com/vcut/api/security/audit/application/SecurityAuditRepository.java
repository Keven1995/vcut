package com.vcut.api.security.audit.application;

import com.vcut.api.security.audit.domain.SecurityAuditEvent;
import java.time.Instant;

public interface SecurityAuditRepository {

  void save(SecurityAuditEvent event);

  int deleteExpiredBefore(Instant cutoff);
}
