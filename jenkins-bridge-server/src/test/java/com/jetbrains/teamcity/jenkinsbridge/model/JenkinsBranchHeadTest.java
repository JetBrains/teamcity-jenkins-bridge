package com.jetbrains.teamcity.jenkinsbridge.model;

import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsRefType;
import com.jetbrains.teamcity.jenkinsbridge.xml.JaxbUnmarshaller;
import com.jetbrains.teamcity.jenkinsbridge.xml.XmlReaderFactory;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JenkinsBranchHeadTest {
  private static final JaxbUnmarshaller XML_UNMARSHALLER = new JaxbUnmarshaller(new XmlReaderFactory());

  private static final String GITHUB_TAG_CONFIG_XML = """
      <flow-definition plugin="workflow-job@1571.1580.v18e46842c125">
          <properties>
              <org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty plugin="workflow-multibranch@821.vc3b_4ea_780798">
                  <branch plugin="branch-api@2.1280.v0d4e5b_b_460ef">
                      <head class="org.jenkinsci.plugins.github_branch_source.GitHubTagSCMHead" plugin="github-branch-source@1967.1970.vd86979736546">
                          <name>v1.0</name>
                      </head>
                  </branch>
              </org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty>
          </properties>
      </flow-definition>
      """;

  private static final String GITLAB_TAG_CONFIG_XML = """
      <flow-definition plugin="workflow-job@1571.1580.v18e46842c125">
          <properties>
              <org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty plugin="workflow-multibranch@821.vc3b_4ea_780798">
                  <branch plugin="branch-api@2.1280.v0d4e5b_b_460ef">
                      <head class="io.jenkins.plugins.gitlabbranchsource.GitLabTagSCMHead" plugin="gitlab-branch-source@740.v04f287f9194d">
                          <name>v1.0</name>
                      </head>
                  </branch>
              </org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty>
          </properties>
      </flow-definition>
      """;

  private static final String BITBUCKET_TAG_CONFIG_XML = """
      <flow-definition plugin="workflow-job@1571.1580.v18e46842c125">
          <properties>
              <org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty plugin="workflow-multibranch@821.vc3b_4ea_780798">
                  <branch plugin="branch-api@2.1280.v0d4e5b_b_460ef">
                      <head class="com.cloudbees.jenkins.plugins.bitbucket.BitbucketTagSCMHead" plugin="cloudbees-bitbucket-branch-source@937.3.6">
                          <name>v1.0</name>
                      </head>
                  </branch>
              </org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty>
          </properties>
      </flow-definition>
      """;

  private static final String REGULAR_BRANCH_CONFIG_XML = """
      <flow-definition plugin="workflow-job@1571.1580.v18e46842c125">
          <properties>
              <org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty plugin="workflow-multibranch@821.vc3b_4ea_780798">
                  <branch plugin="branch-api@2.1280.v0d4e5b_b_460ef">
                      <head class="jenkins.plugins.git.GitBranchSCMHead" plugin="git@5.10.1">
                          <name>main</name>
                      </head>
                  </branch>
              </org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty>
          </properties>
      </flow-definition>
      """;

  @Test
  public void refTypeIsTagsForGitHubTagHead() {
    assertEquals(VcsRefType.TAGS, JenkinsBranchHead.refType(GITHUB_TAG_CONFIG_XML, XML_UNMARSHALLER));
  }

  @Test
  public void refTypeIsTagsForGitlabTagHead() {
    assertEquals(VcsRefType.TAGS, JenkinsBranchHead.refType(GITLAB_TAG_CONFIG_XML, XML_UNMARSHALLER));
  }

  @Test
  public void refTypeIsTagsForBitbucketTagHead() {
    assertEquals(VcsRefType.TAGS, JenkinsBranchHead.refType(BITBUCKET_TAG_CONFIG_XML, XML_UNMARSHALLER));
  }

  @Test
  public void refTypeIsHeadsForRegularBranch() {
    assertEquals(VcsRefType.HEADS, JenkinsBranchHead.refType(REGULAR_BRANCH_CONFIG_XML, XML_UNMARSHALLER));
  }

  @Test
  public void refTypeIsHeadsForBlankOrMalformedInput() {
    assertEquals(VcsRefType.HEADS, JenkinsBranchHead.refType(null, XML_UNMARSHALLER));
    assertEquals(VcsRefType.HEADS, JenkinsBranchHead.refType("", XML_UNMARSHALLER));
    assertEquals(VcsRefType.HEADS, JenkinsBranchHead.refType("not xml at all", XML_UNMARSHALLER));
  }

  @Test
  public void readsConcreteTagScmFromMultibranchChild() {
    JenkinsScmHeadInfo info = JenkinsBranchHead.scmInfo(tagScmXml(
        "org.jenkinsci.plugins.github_branch_source.GitHubTagSCMHead",
        "<url>git@github.com:org/repo.git</url>"), XML_UNMARSHALLER).orElseThrow();

    assertEquals(VcsRefType.TAGS, info.refType());
    assertEquals("v1.0", info.headName());
    assertEquals("source-1", info.sourceId());
    assertEquals("git@github.com:org/repo.git", info.remoteUrl());
  }

  @Test
  public void acceptsEquivalentDuplicateRemotesButRejectsAmbiguousOnes() {
    assertTrue(JenkinsBranchHead.scmInfo(tagScmXml(
        "io.jenkins.plugins.gitlabbranchsource.GitLabTagSCMHead",
        "<url>git@github.com:org/repo.git</url><url>https://github.com/org/repo.git</url>"),
        XML_UNMARSHALLER).isPresent());
    assertFalse(JenkinsBranchHead.scmInfo(tagScmXml(
        "com.cloudbees.jenkins.plugins.bitbucket.BitbucketTagSCMHead",
        "<url>https://example.test/one.git</url><url>https://example.test/two.git</url>"),
        XML_UNMARSHALLER).isPresent());
  }

  private static String tagScmXml(String headClass, String urls) {
    StringBuilder remotes = new StringBuilder();
    for (String url : urls.split("(?=<url>)")) {
      if (!url.isEmpty()) {
        remotes.append("<hudson.plugins.git.UserRemoteConfig>").append(url)
            .append("</hudson.plugins.git.UserRemoteConfig>");
      }
    }
    return "<flow-definition><properties>"
        + "<org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty><branch>"
        + "<sourceId>source-1</sourceId><head class=\"" + headClass + "\"><name>v1.0</name></head>"
        + "<scm class=\"hudson.plugins.git.GitSCM\"><userRemoteConfigs>" + remotes
        + "</userRemoteConfigs></scm></branch>"
        + "</org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty>"
        + "</properties></flow-definition>";
  }
}
