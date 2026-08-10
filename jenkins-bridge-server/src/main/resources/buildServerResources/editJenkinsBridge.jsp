<%@ include file="/include-internal.jsp" %>
<%@ page import="com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants" %>
<jsp:useBean id="buildForm" scope="request" type="jetbrains.buildServer.controllers.admin.projects.EditableBuildTypeSettingsForm"/>
<jsp:useBean id="jenkinsConnections" scope="request" type="com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnectionResolver"/>
<%--
  Edit page for the "Jenkins Bridge" build feature. This build configuration is itself the mirror
  target, so there is no build-type picker. The Jenkins server and its credentials come from a
  "Jenkins" connection on the project, selected below.
--%>
<c:set var="connections" value="${jenkinsConnections.availableConnections(buildForm.project)}"/>
<c:set var="connectionUrls" value="${jenkinsConnections.connectionUrls(buildForm.project)}"/>
<c:set var="connectionsPageUrl" value="${jenkinsConnections.connectionsPageUrl(buildForm.project)}"/>

<tr>
  <td><label for="<%=BridgeBuildFeatureConstants.PARAM_CONNECTION_ID%>">Jenkins connection: <l:star/></label></td>
  <td>
    <props:selectProperty name="<%=BridgeBuildFeatureConstants.PARAM_CONNECTION_ID%>"
                          className="longField"
                          disabled="${empty connections}">
      <props:option value="">-- Select a Jenkins connection --</props:option>
      <c:forEach var="connection" items="${connections}">
        <props:option value="${connection.id}"><c:out
            value="${connection.displayName} (${connectionUrls[connection.id]})"/></props:option>
      </c:forEach>
    </props:selectProperty>
    <c:choose>
      <c:when test="${empty connections}">
        <span class="smallNote">
          This project has no Jenkins connection yet. Add one on the
          <a href="<c:url value="${connectionsPageUrl}"/>">Connections</a> page first.
        </span>
      </c:when>
      <c:otherwise>
        <span class="smallNote">
          Jenkins server to mirror from, managed on the project's
          <a href="<c:url value="${connectionsPageUrl}"/>">Connections</a> page.
        </span>
      </c:otherwise>
    </c:choose>
    <span class="error" id="error_<%=BridgeBuildFeatureConstants.PARAM_CONNECTION_ID%>"></span>
  </td>
</tr>

<tr>
  <td><label for="<%=BridgeBuildFeatureConstants.PARAM_JENKINS_JOB%>">Jenkins job path: <l:star/></label></td>
  <td>
    <props:textProperty name="<%=BridgeBuildFeatureConstants.PARAM_JENKINS_JOB%>" className="longField" maxlength="256"/>
    <span class="smallNote">Folder/job path on the Jenkins server, e.g. <code>team/my-pipeline</code>.</span>
    <span class="error" id="error_<%=BridgeBuildFeatureConstants.PARAM_JENKINS_JOB%>"></span>
  </td>
</tr>

<tr>
  <td><label for="<%=BridgeBuildFeatureConstants.PARAM_JENKINS_URL%>">Jenkins pipeline URL (read-only):</label></td>
  <td>
    <props:textProperty name="<%=BridgeBuildFeatureConstants.PARAM_JENKINS_URL%>" className="longField" maxlength="512"
                        style="color: var(--ring-secondary-color);
                               border-color: var(--ring-border-disabled-color);
                               background-color: var(--ring-disabled-background-color);"/>
  </td>
</tr>

<tr>
  <td><label for="<%=BridgeBuildFeatureConstants.PARAM_RECENT_LIMIT%>">No. of builds to import on first sync:</label></td>
  <td>
    <props:textProperty name="<%=BridgeBuildFeatureConstants.PARAM_RECENT_LIMIT%>" className="longField" maxlength="256"/>
    <span class="smallNote">
      Number of Jenkins builds to import when syncing for the first time.
      Limited to builds available in Jenkins.
      Changing it later has no effect.
      Default: <code><%=BridgeBuildFeatureConstants.DEFAULT_RECENT_LIMIT%></code> (the most recent build).
    </span>
    <span class="error" id="error_<%=BridgeBuildFeatureConstants.PARAM_RECENT_LIMIT%>"></span>
  </td>
</tr>

<script type="text/javascript">
  (function () {
    var connectionUrls = ${jenkinsConnections.connectionUrlsAsJson(buildForm.project)};

    var connectionSelect = document.getElementById('<%=BridgeBuildFeatureConstants.PARAM_CONNECTION_ID%>');
    var jobPathInput = document.getElementById('<%=BridgeBuildFeatureConstants.PARAM_JENKINS_JOB%>');
    var jobUrlInput = document.getElementById('<%=BridgeBuildFeatureConstants.PARAM_JENKINS_URL%>');
    var recentLimitInput = document.getElementById('<%=BridgeBuildFeatureConstants.PARAM_RECENT_LIMIT%>');

    if (recentLimitInput) {
      recentLimitInput.type = 'number';
      recentLimitInput.min = '0';
      recentLimitInput.step = '1';
      if (!recentLimitInput.value) {
        recentLimitInput.value = '<%=BridgeBuildFeatureConstants.DEFAULT_RECENT_LIMIT%>';
      }
    }

    if (!connectionSelect || !jobPathInput || !jobUrlInput) {
      return;
    }

    jobUrlInput.readOnly = true;

    // Preselect when the project offers exactly one connection and none was chosen yet.
    var connectionIds = Object.keys(connectionUrls);
    if (!connectionSelect.value && connectionIds.length === 1) {
      connectionSelect.value = connectionIds[0];
    }

    // Mirrors JenkinsClient.jenkinsJobPath: every path segment becomes /job/<segment>.
    function jobUrlFor(baseUrl, jobPath) {
      if (!baseUrl) {
        return '';
      }
      var url = baseUrl;
      var segments = (jobPath || '').split('/');
      for (var i = 0; i < segments.length; i++) {
        var segment = segments[i].trim();
        if (segment.length > 0) {
          url += '/job/' + encodeURIComponent(segment);
        }
      }
      return url;
    }

    function refreshJobUrl() {
      jobUrlInput.value = jobUrlFor(connectionUrls[connectionSelect.value], jobPathInput.value);
    }

    connectionSelect.onchange = refreshJobUrl;
    jobPathInput.onchange = refreshJobUrl;
    jobPathInput.onkeyup = refreshJobUrl;
    refreshJobUrl();
  })();
</script>
