package com.jetbrains.teamcity.jenkinsbridge;

import org.junit.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class KotlinDslDescriptorTest {
  @Test
  public void describesJenkinsConnection() throws Exception {
    Document document = read("kotlin-dsl/projectFeatures/JenkinsConnection.xml");
    assertEquals("projectFeature", document.getDocumentElement().getAttribute("kind"));
    assertEquals("OAuthProvider", document.getDocumentElement().getAttribute("type"));
    assertEquals("JenkinsConnection", document.getElementsByTagName("class").item(0).getAttributes().getNamedItem("name").getNodeValue());
    assertEquals("jenkinsConnection", document.getElementsByTagName("function").item(0).getAttributes().getNamedItem("name").getNodeValue());
    assertEquals(Set.of("displayName", "jenkinsUrl", "jenkinsUser", "secure:jenkinsToken"), parameterNames(document));
  }

  @Test
  public void describesJenkinsBridgeFeature() throws Exception {
    Document document = read("kotlin-dsl/buildFeatures/JenkinsBridge.xml");
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
