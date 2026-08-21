<%@ include file="/include-internal.jsp" %>
<%@ taglib prefix="oauth" tagdir="/WEB-INF/tags/oauth" %>
<%@ page import="com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnectionConstants" %>
<%@ page import="com.jetbrains.teamcity.jenkinsbridge.util.TeamCityUiSettings" %>
<jsp:useBean id="project" type="jetbrains.buildServer.serverSide.SProject" scope="request"/>
<jsp:useBean id="oauthProvider" type="com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnectionProvider" scope="request"/>
<% boolean readOnly = TeamCityUiSettings.isReadOnly(project); %>

<oauth:displayName />

<tr>
  <th><label for="<%=JenkinsConnectionConstants.PARAM_URL%>">URL:</label><l:star/></th>
  <td>
    <props:textProperty name="<%=JenkinsConnectionConstants.PARAM_URL%>" className="longField" maxlength="512"/>
    <span class="smallNote">Jenkins URL.</span>
    <span class="error" id="error_<%=JenkinsConnectionConstants.PARAM_URL%>"></span>
  </td>
</tr>

<c:if test="<%=readOnly%>">
  <tr>
    <td colspan="2">
      <span class="smallNote">Project settings are read-only. Existing connection details can be tested but not saved.</span>
    </td>
  </tr>
</c:if>

<tr>
  <th><label for="<%=JenkinsConnectionConstants.PARAM_USER%>">Username:</label><l:star/></th>
  <td>
    <props:textProperty name="<%=JenkinsConnectionConstants.PARAM_USER%>" className="longField" maxlength="256"/>
    <span class="smallNote">Jenkins user the API token below belongs to.</span>
    <span class="error" id="error_<%=JenkinsConnectionConstants.PARAM_USER%>"></span>
  </td>
</tr>

<tr>
  <th><label for="<%=JenkinsConnectionConstants.PARAM_TOKEN%>">Token:</label><l:star/></th>
  <td>
    <props:passwordProperty name="<%=JenkinsConnectionConstants.PARAM_TOKEN%>" className="longField"/>
    <span class="smallNote">
      Jenkins API token, created in the user's Security settings.
    </span>
    <span class="error" id="error_<%=JenkinsConnectionConstants.PARAM_TOKEN%>"></span>
  </td>
</tr>

<tr>
  <td colspan="2">
    <em>
      To mirror jobs from this Jenkins server, use the
      <a href="<c:url value="${oauthProvider.importJobsTabUrl(project)}"/>">Jenkins Jobs Sync</a>
      tab of this project, or add the <strong>Jenkins Bridge</strong> build feature to a build configuration.
    </em>
  </td>
</tr>

<script type="text/javascript">
  // Saving is still disabled by TeamCity, but testing an existing connection is non-mutating.
  (function () {
    var readOnly = <%=readOnly%>;
    if (!readOnly) return;
    window.setTimeout(function () {
      var controls = document.querySelectorAll('input, button');
      for (var i = 0; i < controls.length; i++) {
        var control = controls[i];
        var label = (control.value || control.textContent || '').toLowerCase();
        if (label.indexOf('test connection') >= 0) {
          control.disabled = false;
          control.removeAttribute('disabled');
        } else if (control.tagName.toLowerCase() === 'input'
            && control.type !== 'button' && control.type !== 'submit') {
          // Read-only controls must remain successful form controls so the test action receives
          // the displayed values; disabled controls would be omitted from form submission.
          control.disabled = false;
          control.readOnly = true;
          control.removeAttribute('disabled');
        }
      }
    }, 0);
  })();
</script>
