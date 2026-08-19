package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JenkinsCrumbTest {
  @Test
  public void disabledCrumbHasNoNullableHeaderValues() {
    JenkinsCrumb crumb = JenkinsCrumb.disabled();

    assertFalse(crumb.isPresent());
    assertTrue(crumb.getField() == null);
    assertTrue(crumb.getValue() == null);
  }

  @Test
  public void incompleteJenkinsCrumbIsTreatedAsDisabled() {
    JenkinsCrumb crumb = JenkinsCrumb.fromJson(
        JsonParser.parseString("{\"crumbRequestField\":\"Jenkins-Crumb\"}").getAsJsonObject());

    assertFalse(crumb.isPresent());
  }
}
