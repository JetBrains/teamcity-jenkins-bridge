package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpClient;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettings;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettingsProvider;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

/**
 * @deprecated use the in-process TeamCity server API instead.
 */
@Deprecated
public class TeamCityClient {
  private static final String APPLICATION_JSON = "application/json";
  private static final String TEXT_PLAIN = "text/plain";
  private static final String AGENTLESS_BUILD_PROPERTY = "teamcity.build.agentLess";

  private final JenkinsBridgeSettingsProvider settingsProvider;
  private final BridgeHttpClient httpClient;
  private final Gson gson = new Gson();

  public TeamCityClient(JenkinsBridgeSettingsProvider settingsProvider, BridgeHttpClient httpClient) {
    this.settingsProvider = settingsProvider;
    this.httpClient = httpClient;
  }


  /**
   * @deprecated use {@link TeamCityRunningBuildLocator#recoverBuildId(String, String)}, which
   * needs no TeamCity credentials.
   */
  // Returns Null if no builds found
  @Deprecated
  public Long findBuildIdByJenkinsBuildKey(String jenkinsBuildKey) throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    String locator = "property:(name:jenkins.build.key,value:" + jenkinsBuildKey + "),count:1,defaultFilter:false";
    String url = teamCityApi(settings, "/builds?locator=" + encodeQueryValue(locator)
        + "&fields=" + encodeQueryValue("build(id)"));

    String response = httpClient.get(
        url,
        settings.getTeamCityUser(),
        settings.getTeamCityPassword(),
        APPLICATION_JSON
    );

    JsonObject root = parseJsonObject(response);
    JsonArray builds = root.getAsJsonArray("build");
    if (builds == null || builds.size() == 0) {
      return null;
    }

    JsonElement id = builds.get(0).getAsJsonObject().get("id");
    if (id == null || id.isJsonNull()) {
      return null;
    }

    return id.getAsLong();
  }

  @Deprecated
  public long queueAgentlessBuild(String buildTypeId, Map<String, String> properties) throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    JsonObject payload = new JsonObject();
    JsonObject buildType = new JsonObject();
    buildType.addProperty("id", buildTypeId);
    payload.add("buildType", buildType);

    JsonArray propertyArray = new JsonArray();
    addProperty(propertyArray, AGENTLESS_BUILD_PROPERTY, "true");
    for (Map.Entry<String, String> property : properties.entrySet()) {
      addProperty(propertyArray, property.getKey(), property.getValue());
    }

    JsonObject propertiesWrapper = new JsonObject();
    propertiesWrapper.add("property", propertyArray);
    payload.add("properties", propertiesWrapper);

    String response = httpClient.post(
        teamCityApi(settings, "/buildQueue"),
        settings.getTeamCityUser(),
        settings.getTeamCityPassword(),
        gson.toJson(payload),
        APPLICATION_JSON,
        APPLICATION_JSON
    );

    return parseJsonObject(response).get("id").getAsLong();
  }

  @Deprecated
  public void markBuildAsRunning(long buildId, String requestor) throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    httpClient.put(
        teamCityApi(settings, "/builds/id:" + buildId + "/runningData"),
        settings.getTeamCityUser(),
        settings.getTeamCityPassword(),
        requestor,
        TEXT_PLAIN,
        null
    );
  }

  @Deprecated
  public void addBuildLog(long buildId, String text) throws BridgeHttpException {
    if (text == null || text.length() == 0) {
      return;
    }

    JenkinsBridgeSettings settings = settingsProvider.load();
    httpClient.post(
        teamCityApi(settings, "/builds/id:" + buildId + "/log"),
        settings.getTeamCityUser(),
        settings.getTeamCityPassword(),
        text,
        TEXT_PLAIN,
        null
    );
  }

  @Deprecated
  public void setBuildFinishDate(long buildId, String finishDate) throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    httpClient.put(
        teamCityApi(settings, "/builds/id:" + buildId + "/finishDate"),
        settings.getTeamCityUser(),
        settings.getTeamCityPassword(),
        finishDate,
        TEXT_PLAIN,
        null
    );
  }

  private String teamCityApi(JenkinsBridgeSettings settings, String path) {
    return settings.getTeamCityUrl() + "/app/rest" + path;
  }

  private JsonObject parseJsonObject(String response) {
    return JsonParser.parseString(response).getAsJsonObject();
  }

  private void addProperty(JsonArray properties, String name, String value) {
    JsonObject property = new JsonObject();
    property.addProperty("name", name);
    property.addProperty("value", nullToEmpty(value));
    properties.add(property);
  }

  private static String encodeQueryValue(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException("UTF-8 is not available", e);
    }
  }
}
