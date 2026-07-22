<%@ taglib prefix="props" tagdir="/WEB-INF/tags/props" %>
<%@ taglib prefix="l" tagdir="/WEB-INF/tags/layout" %>
<%@ taglib prefix="bs" tagdir="/WEB-INF/tags" %>
<%@ page import="com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants" %>
<%--
  Edit page for the "Jenkins Bridge" build feature. This build configuration is itself the mirror
  target, so there is no build-type picker. The Jenkins server URL and credentials are configured
  globally; only the job path (and an optional backfill depth) are per-configuration.
--%>
<tr>
  <td><label for="<%=BridgeBuildFeatureConstants.PARAM_JENKINS_JOB%>">Jenkins job path: <l:star/></label></td>
  <td>
    <props:textProperty name="<%=BridgeBuildFeatureConstants.PARAM_JENKINS_JOB%>" className="longField" maxlength="256"/>
    <span class="smallNote">Folder/job path on the Jenkins server, e.g. <code>team/my-pipeline</code>.</span>
  </td>
</tr>
<tr>
  <td><label for="<%=BridgeBuildFeatureConstants.PARAM_JENKINS_URL%>">Jenkins job URL:</label></td>
  <td>
    <props:textProperty name="<%=BridgeBuildFeatureConstants.PARAM_JENKINS_URL%>" className="longField" maxlength="512"/>
    <span class="smallNote">Optional, informational (set automatically on import). The poller uses the global Jenkins URL plus the job path.</span>
  </td>
</tr>
<tr>
  <td><label for="<%=BridgeBuildFeatureConstants.PARAM_RECENT_LIMIT%>">Recent build backfill:</label></td>
  <td>
    <props:textProperty name="<%=BridgeBuildFeatureConstants.PARAM_RECENT_LIMIT%>" className="longField" maxlength="8"/>
    <span class="smallNote">Optional. Cold-start backfill depth; leave blank to use the global default.</span>
  </td>
</tr>
<tr>
  <td><label for="<%=BridgeBuildFeatureConstants.PARAM_IN_MULTIBRANCH%>">Multibranch pipeline job:</label></td>
  <td>
    <props:checkboxProperty name="<%=BridgeBuildFeatureConstants.PARAM_IN_MULTIBRANCH%>"/>
    <span class="smallNote">Whether this configuration mirrors a Jenkins multibranch pipeline.</span>
  </td>
</tr>
