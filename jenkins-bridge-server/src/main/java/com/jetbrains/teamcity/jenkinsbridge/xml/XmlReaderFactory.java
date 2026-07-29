package com.jetbrains.teamcity.jenkinsbridge.xml;

import org.jetbrains.annotations.NotNull;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;

/**
 * Creates readers over XML that comes from outside TeamCity, such as a Jenkins config.xml.
 */
public class XmlReaderFactory {

  /**
   * Creates a reader over one document.
   *
   * @param xml The document to read.
   * @return The reader who the caller closes.
   */
  @NotNull
  public XMLStreamReader createReader(@NotNull String xml) throws XMLStreamException {
    XMLInputFactory inputFactory = XMLInputFactory.newFactory();
    inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    inputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    return inputFactory.createXMLStreamReader(new StringReader(xml));
  }
}
