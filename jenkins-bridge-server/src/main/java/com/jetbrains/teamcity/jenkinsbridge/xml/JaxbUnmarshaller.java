package com.jetbrains.teamcity.jenkinsbridge.xml;

import com.intellij.openapi.diagnostic.Logger;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.xml.stream.XMLStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads XML into classes that describe its shape with JAXB annotations.
 */
public class JaxbUnmarshaller {
  private static final Logger LOG = Logger.getInstance(JaxbUnmarshaller.class.getName());

  private final XmlReaderFactory readerFactory;
  private final Map<Class<?>, JAXBContext> contexts = new HashMap<>();

  public JaxbUnmarshaller(@NotNull XmlReaderFactory readerFactory) {
    this.readerFactory = readerFactory;
  }

  /**
   * Reads one document.
   *
   * @param xml  The document to read, which may be null or blank.
   * @param type The annotated class that describes the document, whose root element it maps.
   * @return The document read into {@code type}, or {@link Optional#empty()} when it is blank or
   *     cannot be read.
   */
  @NotNull
  public <T> Optional<T> unmarshal(@Nullable String xml, @NotNull Class<T> type) {
    if (xml == null || xml.trim().isEmpty()) {
      LOG.warn("Cannot read an empty document as " + type.getSimpleName());
      return Optional.empty();
    }

    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    try {
      // JAXB and StAX find their runtimes with a ServiceLoader on the thread context classloader,
      // which on a TeamCity server thread is the web app classloader. That one only carries the old
      // javax.xml.bind runtime, so the lookup has to be pointed at the plugin classloader, which
      // carries the bundled jakarta.xml.bind one.
      // TODO: Decide whether to update the core's JAXB runtime to jakarta.xml.bind 4.x.x, to
      //  downgrade the JAXB runtime version in the plugin, or revert the plugin to manual DOM parsing.
      Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

      XMLStreamReader reader = readerFactory.createReader(xml);
      try {
        Object unmarshalled = context(type).createUnmarshaller().unmarshal(reader);
        if (!type.isInstance(unmarshalled)) {
          LOG.error("The root element of the document does not describe a " + type.getSimpleName());
          return Optional.empty();
        }
        return Optional.of(type.cast(unmarshalled));
      } finally {
        reader.close();
      }
    } catch (Exception | Error e) {
      LOG.error("Failed to read a document as " + type.getSimpleName(), e);
      return Optional.empty();
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  /**
   * The shared context for one annotated class. Building a context is expensive and the result is
   * thread safe, so it is built once per class.
   *
   * @param type The annotated class.
   * @return The context.
   */
  @NotNull
  private synchronized JAXBContext context(@NotNull Class<?> type) throws JAXBException {
    JAXBContext context = contexts.get(type);
    if (context == null) {
      context = JAXBContext.newInstance(type);
      contexts.put(type, context);
    }
    return context;
  }
}
