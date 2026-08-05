package com.jetbrains.teamcity.jenkinsbridge.feature;

import jetbrains.buildServer.serverSide.InvalidProperty;
import jetbrains.buildServer.serverSide.PropertiesProcessor;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BridgeBuildFeatureTest {
  private final BridgeBuildFeature feature = new BridgeBuildFeature(stubPluginDescriptor());

  @Test
  public void exposesStableTypeAndAgentlessFlags() {
    assertEquals("jenkinsBridge", feature.getType());
    assertEquals("Jenkins Bridge", feature.getDisplayName());
    assertFalse(feature.isMultipleFeaturesPerBuildTypeAllowed());
    assertFalse(feature.isRequiresAgent());
    assertEquals("plugins/jenkins-bridge/editJenkinsBridge.html", feature.getEditParametersUrl());
  }

  @Test
  public void getParametersProcessorRejectsMissingConnectionAndJobPath() {
    Collection<InvalidProperty> errors = process(new HashMap<String, String>());
    assertEquals(2, errors.size());
    assertEquals(Set.of("connectionId", "jenkinsJob"), propertyNames(errors));
  }

  @Test
  public void getParametersProcessorRejectsMissingConnection() {
    Map<String, String> props = new HashMap<>();
    props.put("jenkinsJob", "team/pipeline");

    Collection<InvalidProperty> errors = process(props);

    assertEquals(1, errors.size());
    assertEquals("connectionId", errors.iterator().next().getPropertyName());
  }

  @Test
  public void getParametersProcessorAcceptsConnectionAndJobPath() {
    assertTrue(process(feature("team/pipeline", null)).isEmpty());
  }

  @Test
  public void getParametersProcessorAcceptsBlankZeroAndPositiveRecentLimits() {
    assertTrue(process(feature("job", "")).isEmpty());
    assertTrue(process(feature("job", "0")).isEmpty());
    assertTrue(process(feature("job", "5")).isEmpty());
  }

  @Test
  public void getParametersProcessorRejectsANegativeOrUnparseableRecentLimit() {
    Collection<InvalidProperty> errors = process(feature("job", "abc"));
    assertEquals(1, errors.size());
    assertEquals("recentBuildLimit", errors.iterator().next().getPropertyName());

    assertEquals(1, process(feature("job", "-1")).size());
  }

  private static Map<String, String> feature(String jobPath, String recentLimit) {
    Map<String, String> props = new HashMap<>();
    props.put("connectionId", "PROJECT_EXT_1_OAuthProvider_1");
    props.put("jenkinsJob", jobPath);
    if (recentLimit != null) {
      props.put("recentBuildLimit", recentLimit);
    }
    return props;
  }

  private static Set<String> propertyNames(Collection<InvalidProperty> errors) {
    Set<String> names = new HashSet<>();
    for (InvalidProperty error : errors) {
      names.add(error.getPropertyName());
    }
    return names;
  }

  private Collection<InvalidProperty> process(Map<String, String> props) {
    PropertiesProcessor processor = feature.getParametersProcessor();
    return processor.process(props);
  }

  private static PluginDescriptor stubPluginDescriptor() {
    return new PluginDescriptor() {
      @NotNull
      @Override
      public String getPluginResourcesPath() {
        return "plugins/jenkins-bridge/";
      }

      @NotNull
      @Override
      public String getPluginResourcesPath(String path) {
        return "plugins/jenkins-bridge/" + path;
      }

      @NotNull
      @Override
      public String getPluginName() {
        return "jenkins-bridge";
      }

      @Override
      public String getPluginVersion() {
        return "test";
      }

      @Override
      public String getParameterValue(String key) {
        return null;
      }

      @Override
      public java.io.File getPluginRoot() {
        return null;
      }
    };
  }
}
