<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="bs" tagdir="/WEB-INF/tags" %>
<%--
  "Import Jenkins Jobs" project-settings tab. Lists top-level Jenkins jobs at a folder path and
  creates a build configuration (with the Jenkins Bridge feature) for each selected job in this
  project. Uses BS.ajaxRequest so TeamCity's CSRF token is attached automatically.
--%>
<div class="jenkinsBridgeImport">
  <h2 class="noBorder">Import Jenkins jobs</h2>
  <p class="grayNote">
    Lists the top-level jobs at a Jenkins folder path (blank = server root) and creates one build
    configuration per selected job in this project. Selecting an already-imported job refreshes
    Jenkins parameters on the existing build configuration. Folders cannot be selected, and
    multibranch projects are expanded by the importer.
  </p>

<%-- TODO: Select the Jenkins connection and add the search bar --%>

  <table class="runnerFormTable">
    <tr>
      <th><label for="jbFolderPath">Jenkins folder path:</label></th>
      <td>
        <input type="text" id="jbFolderPath" class="longField" value=""/>
        <span class="smallNote">Blank = server root. Example: <code>teamA</code>.</span>
      </td>
    </tr>
  </table>

  <div style="margin: 0.5em 0;">
    <input type="button" class="btn" id="jbListBtn" value="List jobs"/>
    <input type="button" class="btn btn_primary" id="jbImportBtn" value="Import / refresh selected" disabled="disabled"/>
    <span id="jbStatus" class="grayNote" style="margin-left: 1em;"></span>
  </div>

  <div id="jbJobs"></div>
  <div id="jbResult" style="margin-top: 1em;"></div>
</div>

<script type="text/javascript">
  (function () {
    var url = '${controllerUrl}';
    var projectExternalId = '${projectExternalId}';

    var folderInput = document.getElementById('jbFolderPath');
    var listBtn = document.getElementById('jbListBtn');
    var importBtn = document.getElementById('jbImportBtn');
    var status = document.getElementById('jbStatus');
    var jobsDiv = document.getElementById('jbJobs');
    var resultDiv = document.getElementById('jbResult');

    function esc(s) {
      return (s == null ? '' : String(s)).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    function encode(s) { return encodeURIComponent(s == null ? '' : s); }

    function setStatus(text) { status.innerHTML = esc(text); }

    function renderJobs(jobs) {
      resultDiv.innerHTML = '';
      if (!jobs || !jobs.length) {
        jobsDiv.innerHTML = '<p class="grayNote">No jobs found at this path.</p>';
        importBtn.disabled = true;
        return;
      }
      var html = '<table class="parametersTable" style="width:auto;"><tr><th></th><th>Job</th><th>Type</th></tr>';
      for (var i = 0; i < jobs.length; i++) {
        var j = jobs[i];
        var selectable = j.importable || j.isMultibranch;
        var note = j.alreadyImported ? ' <span class="grayNote">(already imported; refresh parameters)</span>'
          : (j.isMultibranch ? ' <span class="grayNote">(multibranch pipeline)</span>'
          : (!j.importable ? ' <span class="grayNote">(folder)</span>' : ''));
        html += '<tr>'
          + '<td><input type="checkbox" class="jbJob" value="' + esc(j.fullName) + '"' + (selectable ? '' : ' disabled="disabled"') + '/></td>'
          + '<td>' + esc(j.fullName) + note + '</td>'
          + '<td class="grayNote">' + esc(j.type) + '</td>'
          + '</tr>';
      }
      html += '</table>';
      jobsDiv.innerHTML = html;
      importBtn.disabled = false;
    }

    function renderResult(result) {
      var parts = [];
      function section(title, entries, cls) {
        if (!entries || !entries.length) return;
        var s = '<div class="' + cls + '"><strong>' + esc(title) + ' (' + entries.length + ')</strong><ul>';
        for (var i = 0; i < entries.length; i++) {
          s += '<li>' + esc(entries[i].jenkinsJob) + ' &mdash; ' + esc(entries[i].detail) + '</li>';
        }
        parts.push(s + '</ul></div>');
      }
      section('Created', result.created, 'successMessage');
      section('Skipped', result.skipped, 'grayNote');
      section('Failed', result.failed, 'errorMessage');
      resultDiv.innerHTML = parts.join('') || '<p class="grayNote">Nothing to import.</p>';
    }

    function parse(transport) {
      try { return JSON.parse(transport.responseText); } catch (e) { return null; }
    }

    listBtn.onclick = function () {
      jobsDiv.innerHTML = '';
      resultDiv.innerHTML = '';
      importBtn.disabled = true;
      setStatus('Listing jobs…');
      BS.ajaxRequest(url, {
        parameters: 'action=list&projectExternalId=' + encode(projectExternalId) + '&folderPath=' + encode(folderInput.value),
        onComplete: function (transport) {
          var data = parse(transport);
          if (!data) { setStatus('HTTP ' + transport.status + ' @ ' + url + ' — ' + (transport.responseText || '').replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').substring(0, 200)); return; }
          if (data.error) { setStatus('Error: ' + data.error); return; }
          setStatus('');
          renderJobs(data);
        }
      });
    };

    importBtn.onclick = function () {
      var boxes = jobsDiv.querySelectorAll('input.jbJob:checked');
      if (!boxes.length) { setStatus('Select at least one job'); return; }
      var params = 'action=import&projectExternalId=' + encode(projectExternalId);
      for (var i = 0; i < boxes.length; i++) { params += '&job=' + encode(boxes[i].value); }
      setStatus('Importing…');
      importBtn.disabled = true;
      BS.ajaxRequest(url, {
        parameters: params,
        onComplete: function (transport) {
          importBtn.disabled = false;
          var data = parse(transport);
          if (!data) { setStatus('HTTP ' + transport.status + ' @ ' + url + ' — ' + (transport.responseText || '').replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').substring(0, 200)); return; }
          if (data.error) { setStatus('Error: ' + data.error); return; }
          setStatus('Done. Re-list to refresh.');
          renderResult(data);
        }
      });
    };
  })();
</script>
