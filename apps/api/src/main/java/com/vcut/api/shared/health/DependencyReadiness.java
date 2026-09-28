package com.vcut.api.shared.health;

import java.util.Map;

public interface DependencyReadiness {

  Map<String, String> check();
}
