package com.jetbrains.teamcity.jenkinsbridge;

import jetbrains.buildServer.configs.dsl.extensions.PluginDslLogsProcessor;
import jetbrains.buildServer.configs.dsl.extensions.parser.PluginDslExtensionParser;
import jetbrains.buildServer.configs.dsl.extensions.parser.ServerDslResourcesProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class KotlinDslDescriptorTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void teamCityPluginParserDiscoversBothDescriptors() throws Exception {
    File pluginDir = temporaryFolder.newFolder("jenkins-bridge");
    File dslDir = new File(pluginDir, "kotlin-dsl");
    assertTrue(dslDir.mkdir());
    copyResource("kotlin-dsl/JenkinsConnection.xml", new File(dslDir, "JenkinsConnection.xml"));
    copyResource("kotlin-dsl/JenkinsBridge.xml", new File(dslDir, "JenkinsBridge.xml"));

    PluginDslLogsProcessor logs = mock(PluginDslLogsProcessor.class);
    PluginDslExtensionParser parser =
        new PluginDslExtensionParser(logs, new ServerDslResourcesProvider(logs));

    assertEquals(2, parser.parse(pluginDir).getPluginExtensions().size());
  }

  @Test
  public void describesJenkinsConnection() throws Exception {
    Document document = read("kotlin-dsl/JenkinsConnection.xml");
    assertEquals("projectFeature", document.getDocumentElement().getAttribute("kind"));
    assertEquals("OAuthProvider", document.getDocumentElement().getAttribute("type"));
    assertEquals("JenkinsConnection", document.getElementsByTagName("class").item(0).getAttributes().getNamedItem("name").getNodeValue());
    assertEquals("jenkinsConnection", document.getElementsByTagName("function").item(0).getAttributes().getNamedItem("name").getNodeValue());
    assertEquals(Set.of("displayName", "jenkinsUrl", "jenkinsUser", "secure:jenkinsToken"), parameterNames(document));
  }

  @Test
  public void describesJenkinsBridgeFeature() throws Exception {
    Document document = read("kotlin-dsl/JenkinsBridge.xml");
    assertEquals("buildFeature", document.getDocumentElement().getAttribute("kind"));
    assertEquals("jenkinsBridge", document.getDocumentElement().getAttribute("type"));
    assertEquals("JenkinsBridge", document.getElementsByTagName("class").item(0).getAttributes().getNamedItem("name").getNodeValue());
    assertEquals("jenkinsBridge", document.getElementsByTagName("function").item(0).getAttributes().getNamedItem("name").getNodeValue());
    assertEquals(Set.of("connectionId", "jenkinsJob", "recentBuildLimit"), parameterNames(document));
  }

  private static Document read(String path) throws Exception {
    InputStream stream = KotlinDslDescriptorTest.class.getClassLoader().getResourceAsStream(path);
    assertNotNull(path, stream);
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream);
  }

  private static void copyResource(String path, File target) throws Exception {
    try (InputStream stream = KotlinDslDescriptorTest.class.getClassLoader().getResourceAsStream(path)) {
      assertNotNull(path, stream);
      Files.copy(stream, target.toPath());
    }
  }

  private static Set<String> parameterNames(Document document) {
    Set<String> names = new java.util.HashSet<>();
    org.w3c.dom.NodeList params = document.getElementsByTagName("params").item(0).getChildNodes();
    for (int i = 0; i < params.getLength(); i++) {
      if (params.item(i).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
        names.add(params.item(i).getAttributes().getNamedItem("name").getNodeValue());
      }
    }
    return names;
  }
}
