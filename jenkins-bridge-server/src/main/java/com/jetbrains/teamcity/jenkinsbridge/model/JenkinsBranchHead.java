package com.jetbrains.teamcity.jenkinsbridge.model;

import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsRefType;
import com.jetbrains.teamcity.jenkinsbridge.xml.JaxbUnmarshaller;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.normalizeRepositoryUrl;
import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

/**
 * Determines whether a multibranch pipeline branch job's config.xml describes a tag or a regular branch,
 * from the {@code class} attribute of its declared branch head.
 */
public final class JenkinsBranchHead {
  private JenkinsBranchHead() {
  }

  @NotNull
  public static VcsRefType refType(@Nullable String xml, @NotNull JaxbUnmarshaller unmarshaller) {
    return unmarshaller.unmarshal(xml, FlowDefinition.class)
        .map(JenkinsBranchHead::headClass)
        .map(VcsRefType::fromHeadClass)
        .orElse(VcsRefType.HEADS);
  }

  /**
   * Reads the concrete SCM stored on a multibranch child. Jenkins serializes this {@code GitSCM}
   * independently of whether the parent source is GitHub, GitLab, Bitbucket, or generic Git.
   */
  @NotNull
  public static Optional<JenkinsScmHeadInfo> scmInfo(
      @Nullable String xml,
      @NotNull JaxbUnmarshaller unmarshaller
  ) {
    return unmarshaller.unmarshal(xml, FlowDefinition.class).flatMap(JenkinsBranchHead::scmInfo);
  }

  @NotNull
  private static Optional<JenkinsScmHeadInfo> scmInfo(FlowDefinition flow) {
    Branch branch = branch(flow);
    if (branch == null || branch.head == null || branch.scm == null) {
      return Optional.empty();
    }
    String headClass = nullToEmpty(branch.head.headClass);
    String headName = nullToEmpty(branch.head.name).trim();
    if (headClass.isEmpty() || headName.isEmpty()
        || !nullToEmpty(branch.scm.scmClass).contains("hudson.plugins.git.GitSCM")) {
      return Optional.empty();
    }

    Map<String, String> normalizedToOriginal = new LinkedHashMap<>();
    if (branch.scm.userRemoteConfigs != null) {
      for (UserRemoteConfig remote : branch.scm.userRemoteConfigs) {
        String url = remote == null ? "" : nullToEmpty(remote.url).trim();
        String normalized = normalizeRepositoryUrl(url);
        if (normalized != null) {
          normalizedToOriginal.putIfAbsent(normalized, url);
        }
      }
    }
    if (normalizedToOriginal.size() != 1) {
      return Optional.empty();
    }
    return Optional.of(new JenkinsScmHeadInfo(
        VcsRefType.fromHeadClass(headClass),
        headName,
        nullToEmpty(branch.sourceId),
        normalizedToOriginal.values().iterator().next()));
  }

  @NotNull
  private static String headClass(FlowDefinition flow) {
    Branch branch = branch(flow);
    Head head = branch == null ? null : branch.head;
    return head == null ? "" : nullToEmpty(head.headClass);
  }

  @Nullable
  private static Branch branch(FlowDefinition flow) {
    return flow.properties == null || flow.properties.branchJobProperty == null
        ? null
        : flow.properties.branchJobProperty.branch;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  @XmlRootElement(name = "flow-definition")
  private static class FlowDefinition {
    @XmlElement(name = "properties")
    private Properties properties;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  private static class Properties {
    @XmlElement(name = "org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty")
    private BranchJobProperty branchJobProperty;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  private static class BranchJobProperty {
    @XmlElement(name = "branch")
    private Branch branch;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  private static class Branch {
    @XmlElement(name = "sourceId")
    private String sourceId;

    @XmlElement(name = "head")
    private Head head;

    @XmlElement(name = "scm")
    private Scm scm;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  private static class Head {
    @XmlAttribute(name = "class")
    private String headClass;

    @XmlElement(name = "name")
    private String name;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  private static class Scm {
    @XmlAttribute(name = "class")
    private String scmClass;

    @XmlElementWrapper(name = "userRemoteConfigs")
    @XmlElement(name = "hudson.plugins.git.UserRemoteConfig")
    private List<UserRemoteConfig> userRemoteConfigs;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  private static class UserRemoteConfig {
    @XmlElement(name = "url")
    private String url;
  }
}
