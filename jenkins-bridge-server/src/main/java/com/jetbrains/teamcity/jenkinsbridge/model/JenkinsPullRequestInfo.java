package com.jetbrains.teamcity.jenkinsbridge.model;

import com.intellij.openapi.diagnostic.Logger;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsHost;
import com.jetbrains.teamcity.jenkinsbridge.xml.JaxbUnmarshaller;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.firstNonBlankString;
import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

@SuppressWarnings("unused")
public record JenkinsPullRequestInfo(
    @NotNull String number,
    @NotNull String sourceBranch,
    @NotNull String targetBranch,
    @NotNull String author,
    @NotNull String title
) {
  private static final Logger LOG = Logger.getInstance(JenkinsPullRequestInfo.class.getName());

  public JenkinsPullRequestInfo {
    number = nullToEmpty(number);
    sourceBranch = nullToEmpty(sourceBranch);
    targetBranch = nullToEmpty(targetBranch);
    author = nullToEmpty(author);
    title = nullToEmpty(title);
  }

  /**
   * Parses a multibranch branch job's config.xml. Returns {@link Optional#empty()} when the branch is not a
   * pull or merge request, or the XML is blank or cannot be parsed.
   *
   * @param xml          The body of a {@code .../job/<branch>/config.xml} response.
   * @param unmarshaller The unmarshaller that reads the config.
   * @return The pull or merge request metadata, or {@link Optional#empty()}.
   */
  @NotNull
  public static Optional<JenkinsPullRequestInfo> fromConfigXml(@Nullable String xml,
                                                               @NotNull JaxbUnmarshaller unmarshaller) {
    return unmarshaller.unmarshal(xml, FlowDefinition.class).flatMap(JenkinsPullRequestInfo::fromFlowDefinition);
  }

  /**
   * Maps a parsed config.xml to the pull or merge request it describes.
   *
   * @param flow The parsed config.xml.
   * @return The pull or merge request metadata, or {@link Optional#empty()} for a regular branch.
   */
  @NotNull
  private static Optional<JenkinsPullRequestInfo> fromFlowDefinition(@NotNull FlowDefinition flow) {
    Branch branch = flow.properties == null || flow.properties.branchJobProperty == null
        ? null
        : flow.properties.branchJobProperty.branch;
    Head head = branch == null ? null : branch.head;
    if (head == null) {
      LOG.warn("The branch job config declares no branch head");
      return Optional.empty();
    }

    // GitHub names the source branch sourceBranch, Bitbucket Cloud branchName, and GitLab originName.
    String sourceBranch = firstNonBlankString(head.sourceBranch, head.branchName, head.originName);
    if (sourceBranch.isEmpty()) {
      LOG.warn("The branch head " + nullToEmpty(head.headClass)
          + " declares no pull or merge request source branch");
      return Optional.empty();
    }

    ContributorMetadataAction contributor = branch.actions == null ? null : branch.actions.contributorMetadataAction;
    ObjectMetadataAction objectMetadata = branch.actions == null ? null : branch.actions.objectMetadataAction;
    VcsHost vcsHost = vcsHost(head.headClass);

    // The Bitbucket contributor is an internal account id, which is too technical to be useful.
    String author = contributor == null ? "" : nullToEmpty(vcsHost == VcsHost.BITBUCKET
        ? contributor.contributorDisplayName
        : contributor.contributor);

    return Optional.of(new JenkinsPullRequestInfo(
        firstNonBlankString(head.number, head.id),
        sourceBranch,
        head.target == null ? "" : firstNonBlankString(head.target.name),
        author,
        firstNonBlankString(head.title, objectMetadata == null ? null : objectMetadata.objectDisplayName)));
  }

  /**
   * Reads the hosting provider from the class the branch head is stored as.
   *
   * @param headClass The class attribute of the head element.
   * @return The provider, or null when it is not one of the supported ones.
   */
  @Nullable
  private static VcsHost vcsHost(@Nullable String headClass) {
    String head = nullToEmpty(headClass).toLowerCase(Locale.ROOT);
    if (head.contains("bitbucket")) {
      return VcsHost.BITBUCKET;
    }
    if (head.contains("gitlab")) {
      return VcsHost.GITLAB;
    }
    if (head.contains("github")) {
      return VcsHost.GITHUB;
    }
    return null;
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
    @XmlElement(name = "actions")
    private Actions actions;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  private static class Head {
    @XmlAttribute(name = "class")
    private String headClass;
    @XmlElement(name = "number")
    private String number;
    @XmlElement(name = "id")
    private String id;
    @XmlElement(name = "sourceBranch")
    private String sourceBranch;
    @XmlElement(name = "branchName")
    private String branchName;
    @XmlElement(name = "originName")
    private String originName;
    @XmlElement(name = "title")
    private String title;
    @XmlElement(name = "target")
    private Target target;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  private static class Target {
    @XmlElement(name = "name")
    private String name;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  private static class Actions {
    @XmlElement(name = "jenkins.scm.api.metadata.ContributorMetadataAction")
    private ContributorMetadataAction contributorMetadataAction;
    @XmlElement(name = "jenkins.scm.api.metadata.ObjectMetadataAction")
    private ObjectMetadataAction objectMetadataAction;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  private static class ContributorMetadataAction {
    @XmlElement(name = "contributor")
    private String contributor;
    @XmlElement(name = "contributorDisplayName")
    private String contributorDisplayName;
  }

  @XmlAccessorType(XmlAccessType.FIELD)
  private static class ObjectMetadataAction {
    @XmlElement(name = "objectDisplayName")
    private String objectDisplayName;
  }
}
