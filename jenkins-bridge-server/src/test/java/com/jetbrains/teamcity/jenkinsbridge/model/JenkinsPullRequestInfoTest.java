package com.jetbrains.teamcity.jenkinsbridge.model;

import com.jetbrains.teamcity.jenkinsbridge.xml.JaxbUnmarshaller;
import com.jetbrains.teamcity.jenkinsbridge.xml.XmlReaderFactory;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JenkinsPullRequestInfoTest {
  private static final JaxbUnmarshaller XML_UNMARSHALLER = new JaxbUnmarshaller(new XmlReaderFactory());

  private static final String GITHUB_CONFIG_XML = """
      <flow-definition plugin="workflow-job@1571.1580.v18e46842c125">
          <properties>
              <org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty plugin="workflow-multibranch@821.vc3b_4ea_780798">
                  <branch plugin="branch-api@2.1280.v0d4e5b_b_460ef">
                      <head class="org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead" plugin="github-branch-source@1967.1970.vd86979736546">
                          <name>PR-1</name>
                          <merge>false</merge>
                          <number>1</number>
                          <target>
                              <name>master</name>
                          </target>
                          <sourceOwner>john-doe</sourceOwner>
                          <sourceRepo>building-a-multibranch-pipeline-project</sourceRepo>
                          <sourceBranch>development</sourceBranch>
                      </head>
                      <actions>
                          <jenkins.scm.api.metadata.ContributorMetadataAction plugin="scm-api@728.vc30dcf7a_0df5">
                              <contributor>john-doe</contributor>
                              <contributorDisplayName>John Doe</contributorDisplayName>
                          </jenkins.scm.api.metadata.ContributorMetadataAction>
                          <jenkins.scm.api.metadata.ObjectMetadataAction plugin="scm-api@728.vc30dcf7a_0df5">
                              <objectDisplayName>Merge development into master</objectDisplayName>
                              <objectDescription>Test description</objectDescription>
                          </jenkins.scm.api.metadata.ObjectMetadataAction>
                      </actions>
                  </branch>
              </org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty>
          </properties>
      </flow-definition>
      """;

  private static final String BITBUCKET_CONFIG_XML = """
      <flow-definition plugin="workflow-job@1571.1580.v18e46842c125">
          <properties>
              <org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty plugin="workflow-multibranch@821.vc3b_4ea_780798">
                  <branch plugin="branch-api@2.1280.v0d4e5b_b_460ef">
                      <head class="com.cloudbees.jenkins.plugins.bitbucket.PullRequestSCMHead" plugin="cloudbees-bitbucket-branch-source@937.3.6">
                          <name>PR-1</name>
                          <repoOwner>john-doe</repoOwner>
                          <repository>multibranch-react</repository>
                          <branchName>development</branchName>
                          <branchType>BRANCH</branchType>
                          <number>1</number>
                          <title>Merge development into master</title>
                          <target>
                              <name>master</name>
                          </target>
                      </head>
                      <actions>
                          <jenkins.scm.api.metadata.ContributorMetadataAction plugin="scm-api@728.vc30dcf7a_0df5">
                              <contributor>712020:d05760d0-cab2-4877-a236-d2115fd95349</contributor>
                              <contributorDisplayName>John Doe</contributorDisplayName>
                          </jenkins.scm.api.metadata.ContributorMetadataAction>
                          <jenkins.scm.api.metadata.ObjectMetadataAction plugin="scm-api@728.vc30dcf7a_0df5">
                              <objectDisplayName>Merge development into master</objectDisplayName>
                          </jenkins.scm.api.metadata.ObjectMetadataAction>
                      </actions>
                  </branch>
              </org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty>
          </properties>
      </flow-definition>
      """;

  private static final String GITLAB_CONFIG_XML = """
      <flow-definition plugin="workflow-job@1571.1580.v18e46842c125">
          <properties>
              <org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty plugin="workflow-multibranch@821.vc3b_4ea_780798">
                  <branch plugin="branch-api@2.1280.v0d4e5b_b_460ef">
                      <head class="io.jenkins.plugins.gitlabbranchsource.MergeRequestSCMHead" plugin="gitlab-branch-source@740.v04f287f9194d">
                          <name>MR-1</name>
                          <id>1</id>
                          <target>
                              <name>master</name>
                          </target>
                          <originName>development</originName>
                          <originOwner>john-doe</originOwner>
                          <title>Merge development into master</title>
                      </head>
                      <actions>
                          <jenkins.scm.api.metadata.ObjectMetadataAction plugin="scm-api@728.vc30dcf7a_0df5">
                              <objectDisplayName>Merge development into master</objectDisplayName>
                              <objectDescription/>
                          </jenkins.scm.api.metadata.ObjectMetadataAction>
                          <jenkins.scm.api.metadata.ContributorMetadataAction plugin="scm-api@728.vc30dcf7a_0df5">
                              <contributor>john-doe</contributor>
                              <contributorDisplayName>John Doe</contributorDisplayName>
                          </jenkins.scm.api.metadata.ContributorMetadataAction>
                      </actions>
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
  public void fromConfigXmlParsesGitHubPullRequestHead() {
    var infoResult = JenkinsPullRequestInfo.fromConfigXml(GITHUB_CONFIG_XML, XML_UNMARSHALLER);

    assertTrue(infoResult.isPresent());
    var info = infoResult.get();
    assertEquals("1", info.number());
    assertEquals("development", info.sourceBranch());
    assertEquals("master", info.targetBranch());
    assertEquals("john-doe", info.author());
    assertEquals("Merge development into master", info.title());
  }

  @Test
  public void fromConfigXmlParsesBitbucketPullRequestHead() {
    var infoResult = JenkinsPullRequestInfo.fromConfigXml(BITBUCKET_CONFIG_XML, XML_UNMARSHALLER);

    assertTrue(infoResult.isPresent());
    var info = infoResult.get();
    assertEquals("1", info.number());
    assertEquals("development", info.sourceBranch());
    assertEquals("master", info.targetBranch());
    assertEquals("712020:d05760d0-cab2-4877-a236-d2115fd95349", info.author());
    assertEquals("Merge development into master", info.title());
  }

  @Test
  public void fromConfigXmlParsesGitLabMergeRequestHead() {
    var infoResult = JenkinsPullRequestInfo.fromConfigXml(GITLAB_CONFIG_XML, XML_UNMARSHALLER);

    assertTrue(infoResult.isPresent());
    var info = infoResult.get();
    assertEquals("1", info.number());
    assertEquals("development", info.sourceBranch());
    assertEquals("master", info.targetBranch());
    assertEquals("john-doe", info.author());
    assertEquals("Merge development into master", info.title());
  }

  @Test
  public void fromConfigXmlReturnsEmptyForRegularBranch() {
    var infoResult = JenkinsPullRequestInfo.fromConfigXml(REGULAR_BRANCH_CONFIG_XML, XML_UNMARSHALLER);

    assertFalse(infoResult.isPresent());
  }

  @Test
  public void fromConfigXmlReturnsEmptyForBlankOrNullInput() {
    assertFalse(JenkinsPullRequestInfo.fromConfigXml(null, XML_UNMARSHALLER).isPresent());
    assertFalse(JenkinsPullRequestInfo.fromConfigXml("", XML_UNMARSHALLER).isPresent());
    assertFalse(JenkinsPullRequestInfo.fromConfigXml("   ", XML_UNMARSHALLER).isPresent());
  }

  @Test
  public void fromConfigXmlReturnsEmptyForMalformedXml() {
    assertFalse(JenkinsPullRequestInfo.fromConfigXml("<flow-definition><unterminated>", XML_UNMARSHALLER).isPresent());
    assertFalse(JenkinsPullRequestInfo.fromConfigXml("not xml at all", XML_UNMARSHALLER).isPresent());
  }
}
