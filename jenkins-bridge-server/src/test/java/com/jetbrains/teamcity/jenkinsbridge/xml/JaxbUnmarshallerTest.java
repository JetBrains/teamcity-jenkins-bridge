package com.jetbrains.teamcity.jenkinsbridge.xml;

import org.junit.Test;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class JaxbUnmarshallerTest {
  @Test
  public void malformedXmlReturnsEmpty() {
    JaxbUnmarshaller unmarshaller = new JaxbUnmarshaller(new XmlReaderFactory());

    assertFalse(unmarshaller.unmarshal("not xml", Object.class).isPresent());
  }

  @Test
  public void unexpectedRuntimeFailurePropagates() {
    JaxbUnmarshaller unmarshaller = new JaxbUnmarshaller(new XmlReaderFactory() {
      @Override
      public XMLStreamReader createReader(String xml) throws XMLStreamException {
        throw new IllegalStateException("reader bug");
      }
    });

    try {
      unmarshaller.unmarshal("<flow-definition/>", Object.class);
      fail("Expected runtime failure to propagate");
    } catch (IllegalStateException expected) {
      assertEquals("reader bug", expected.getMessage());
    }
  }
}
