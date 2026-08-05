package com.jetbrains.teamcity.jenkinsbridge.polling;

import java.util.HashMap;
import java.util.Map;

/** Shared per-job locks for the trigger callback and Jenkins discovery paths. */
public class JenkinsJobCoordinator {
  private static final Map<String, Object> locks = new HashMap<String, Object>();

  public synchronized Object lockFor(String controller, String job) {
    String key = (controller == null ? "" : controller) + "\n" + (job == null ? "" : job);
    Object lock = locks.get(key);
    if (lock == null) {
      lock = new Object();
      locks.put(key, lock);
    }
    return lock;
  }
}
