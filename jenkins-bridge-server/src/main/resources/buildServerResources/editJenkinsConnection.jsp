<%@ include file="/include-internal.jsp" %>
<%@ taglib prefix="oauth" tagdir="/WEB-INF/tags/oauth" %>
<%@ page import="com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnectionConstants" %>
<jsp:useBean id="project" type="jetbrains.buildServer.serverSide.SProject" scope="request"/>
<jsp:useBean id="oauthProvider" type="com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnectionProvider" scope="request"/>
<%--
  Parameters of the "Jenkins" connection. Included by TeamCity inside its own runnerFormTable, so
  this page contributes table rows only. The "Test connection" button is added by TeamCity because
  the provider declares a testConnectionEndpoint default property.
--%>

<oauth:displayName />

<tr>
  <th><label for="<%=JenkinsConnectionConstants.PARAM_URL%>">URL:</label><l:star/></th>
  <td>
    <props:textProperty name="<%=JenkinsConnectionConstants.PARAM_URL%>" className="longField" maxlength="512"/>
    <span class="smallNote">Jenkins installation URL.</span>
    <span class="error" id="error_<%=JenkinsConnectionConstants.PARAM_URL%>"></span>
  </td>
</tr>

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
      <a href="<c:url value="${oauthProvider.importJobsTabUrl(project)}"/>">Import Jenkins Jobs</a>
      tab of this project, or add the <strong>Jenkins Bridge</strong> build feature to a build configuration.
    </em>
  </td>
</tr>
