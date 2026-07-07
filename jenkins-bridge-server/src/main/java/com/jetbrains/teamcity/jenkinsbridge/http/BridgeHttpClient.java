package com.jetbrains.teamcity.jenkinsbridge.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BridgeHttpClient {
  public interface StreamHandler {
    void handle(InputStream inputStream) throws IOException;
  }

  public BridgeHttpResponse head(String url, String user, String password, String accept) throws BridgeHttpException {
    return request("HEAD", url, user, password, null, null, accept, null);
  }

  public String get(String url, String user, String password, String accept) throws BridgeHttpException {
    return getResponse(url, user, password, accept).getBody();
  }

  public BridgeHttpResponse getResponse(String url, String user, String password, String accept) throws BridgeHttpException {
    return request("GET", url, user, password, null, null, accept, null);
  }

  public void getStream(String url, String user, String password, String accept, StreamHandler handler)
      throws BridgeHttpException {
    requestStream("GET", url, user, password, accept, handler);
  }

  public String post(String url, String user, String password, String body, String contentType, String accept) throws BridgeHttpException {
    return request("POST", url, user, password, body, contentType, accept, null).getBody();
  }

  /**
   * POST returning the full response (status + headers), with optional extra request headers (e.g. a
   * Jenkins CSRF crumb). Used by the trigger flow, which needs the {@code Location} response header.
   */
  public BridgeHttpResponse postResponse(
      String url, String user, String password, String body, String contentType, String accept,
      Map<String, String> headers) throws BridgeHttpException {
    return request("POST", url, user, password, body, contentType, accept, headers);
  }

  public String put(String url, String user, String password, String body, String contentType, String accept) throws BridgeHttpException {
    return request("PUT", url, user, password, body, contentType, accept, null).getBody();
  }

  private BridgeHttpResponse request(
      String method,
      String url,
      String user,
      String password,
      String body,
      String contentType,
      String accept,
      Map<String, String> headers
  ) throws BridgeHttpException {
    HttpURLConnection connection = null;

    try {
      connection = (HttpURLConnection)new URL(url).openConnection();
      connection.setRequestMethod(method);
      connection.setConnectTimeout(30000);
      connection.setReadTimeout(30000);

      if (isNotBlank(user)) {
        String token = user + ":" + nullToEmpty(password);
        String encoded = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
        connection.setRequestProperty("Authorization", "Basic " + encoded);
      }

      if (isNotBlank(accept)) {
        connection.setRequestProperty("Accept", accept);
      }

      if (headers != null) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
          if (header.getKey() != null && header.getValue() != null) {
            connection.setRequestProperty(header.getKey(), header.getValue());
          }
        }
      }

      if (body != null) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setDoOutput(true);
        if (isNotBlank(contentType)) {
          connection.setRequestProperty("Content-Type", contentType);
        }
        connection.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        OutputStream outputStream = connection.getOutputStream();
        try {
          outputStream.write(bytes);
        } finally {
          outputStream.close();
        }
      }

      int status = connection.getResponseCode();
      String responseBody = readResponseBody(connection, status);

      if (status < 200 || status >= 300) {
        throw new BridgeHttpException(method, url, status, responseBody);
      }

      return new BridgeHttpResponse(status, responseBody, collectHeaders(connection));
    } catch (IOException e) {
      throw new BridgeHttpException(method, url, e);
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  private void requestStream(
      String method,
      String url,
      String user,
      String password,
      String accept,
      StreamHandler handler
  ) throws BridgeHttpException {
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection)new URL(url).openConnection();
      connection.setRequestMethod(method);
      connection.setConnectTimeout(30000);
      connection.setReadTimeout(30000);

      if (isNotBlank(user)) {
        String token = user + ":" + nullToEmpty(password);
        String encoded = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
        connection.setRequestProperty("Authorization", "Basic " + encoded);
      }

      if (isNotBlank(accept)) {
        connection.setRequestProperty("Accept", accept);
      }

      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) {
        String responseBody = readResponseBody(connection, status);
        throw new BridgeHttpException(method, url, status, responseBody);
      }

      InputStream stream = connection.getInputStream();
      try {
        handler.handle(stream);
      } finally {
        stream.close();
      }
    } catch (IOException e) {
      throw new BridgeHttpException(method, url, e);
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  private Map<String, String> collectHeaders(HttpURLConnection connection) {
    Map<String, String> headers = new LinkedHashMap<String, String>();
    Map<String, List<String>> fields = connection.getHeaderFields();
    if (fields != null) {
      for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
        // The status line is exposed under a null key; skip it.
        if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
          headers.put(entry.getKey(), entry.getValue().get(0));
        }
      }
    }
    return headers;
  }

  private String readResponseBody(HttpURLConnection connection, int status) throws IOException {
    InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
    if (stream == null) {
      return "";
    }

    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      int read;
      while ((read = stream.read(chunk)) != -1) {
        buffer.write(chunk, 0, read);
      }
      return new String(buffer.toByteArray(), charsetFor(connection));
    } finally {
      stream.close();
    }
  }

  // Decode using the response's declared charset (e.g. Jenkins consoles that aren't UTF-8),
  // falling back to UTF-8 when absent/unsupported.
  private Charset charsetFor(HttpURLConnection connection) {
    String contentType = connection.getContentType();
    if (contentType != null) {
      for (String part : contentType.split(";")) {
        String trimmed = part.trim();
        if (trimmed.regionMatches(true, 0, "charset=", 0, "charset=".length())) {
          String name = trimmed.substring("charset=".length()).trim().replace("\"", "");
          try {
            if (name.length() > 0 && Charset.isSupported(name)) {
              return Charset.forName(name);
            }
          } catch (RuntimeException ignored) {
            // Illegal/unsupported charset name: fall back to UTF-8.
          }
        }
      }
    }
    return StandardCharsets.UTF_8;
  }

  private boolean isNotBlank(String value) {
    return value != null && value.trim().length() > 0;
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
