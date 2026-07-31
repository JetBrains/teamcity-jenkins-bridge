package com.jetbrains.teamcity.jenkinsbridge.model;

import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsRefType;
import com.jetbrains.teamcity.jenkinsbridge.xml.JaxbUnmarshaller;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

  @NotNull
  private static String headClass(FlowDefinition flow) {
    Branch branch = flow.properties == null || flow.properties.branchJobProperty == null
        ? null
        : flow.properties.branchJobProperty.branch;
    Head head = branch == null ? null : branch.head;
    return head == null ? "" : nullToEmpty(head.headClass);
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
    @XmlElement(name = "head")
    private Head head;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  private static class Head {
    @XmlAttribute(name = "class")
    private String headClass;
  }
}
