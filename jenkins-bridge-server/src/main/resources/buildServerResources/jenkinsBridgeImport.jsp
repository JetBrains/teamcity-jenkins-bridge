<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="bs" tagdir="/WEB-INF/tags" %>
<jsp:useBean id="jenkinsConnections" scope="request" type="com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnectionResolver"/>
<%--
  "Import Jenkins Jobs" project-settings tab. Lists top-level Jenkins jobs from the configured
  Jenkins connection and optionally within a folder path. Creates a build configuration (with the
  Jenkins Bridge feature) for each selected job in this project. Uses BS.ajaxRequest so TeamCity's
  CSRF token is attached automatically.
--%>
<div class="jenkinsBridgeImport">
  <h2 class="noBorder">Import Jenkins jobs</h2>
  <p class="grayNote">
    It creates one build configuration per selected Jenkins job in this project.
    Selecting an already-imported job refreshes Jenkins parameters on the existing build
    configuration. Folders cannot be selected; multibranch projects are expanded by the importer.
  </p>

  <c:set var="connections" value="${jenkinsConnections.availableConnections(project)}"/>

  <table class="runnerFormTable">
    <tr>
      <th><label for="jbConnection">Jenkins connection:</label></th>
      <td>
        <select id="jbConnection" class="longField">
          <option value="">-- Select a Jenkins connection --</option>
          <c:forEach var="connection" items="${connections}">
            <option value="${connection.id}"><c:out value="${connection.displayName}"/></option>
          </c:forEach>
        </select>
        <c:if test="${empty connections}">
          <span class="smallNote">No Jenkins connections are available for this project.</span>
        </c:if>
        <br/>
        <span class="smallNote">
          You can add a new Jenkins connection from the project's
          <a href="<c:url value='${connectionsPageUrl}'/>">Connections</a> tab.
        </span>
      </td>
    </tr>
    <tr>
      <th><label for="jbFolderPath">Jenkins folder path:</label></th>
      <td>
        <input type="text" id="jbFolderPath" class="longField" value=""/>
        <span class="smallNote">Blank = server root. Example: <code>teamA</code>.</span>
      </td>
    </tr>
  </table>

  <div style="margin: 0.5em 0;">
    <input type="button" class="btn" id="jbListBtn" value="List jobs" disabled="disabled"/>
    <input type="button" class="btn btn_primary" id="jbImportBtn" value="Import / refresh selected" disabled="disabled"/>
    <span id="jbStatus" class="grayNote" style="margin-left: 1em;"></span>
  </div>

  <div id="jbConfigured"></div>

  <h3>Available to import</h3>
  <p class="grayNote">Jobs are searched from the selected Jenkins connection and optional folder path.</p>
  <div id="jbControls" style="display:none; margin: 1em 0 0.5em;">
    <label for="jbSearch"><strong>Find available jobs:</strong></label>
    <input type="text" id="jbSearch" class="longField" placeholder="Search by job name or path" disabled="disabled"/>
    <span class="smallNote">Searches jobs from the selected Jenkins connection.</span>
  </div>

  <details id="jbAvailablePanel" open="open">
    <summary><strong>Jobs</strong></summary>
    <div id="jbAvailable"></div>
  </details>
  <div id="jbResult" style="margin-top: 1em;"></div>
</div>

<script type="text/javascript">
  (function () {
    var url = '${controllerUrl}';
    var projectExternalId = '${projectExternalId}';

    var connectionInput = document.getElementById('jbConnection');
    var folderInput = document.getElementById('jbFolderPath');
    var listBtn = document.getElementById('jbListBtn');
    var importBtn = document.getElementById('jbImportBtn');
    var status = document.getElementById('jbStatus');
    var controlsDiv = document.getElementById('jbControls');
    var searchInput = document.getElementById('jbSearch');
    var configuredDiv = document.getElementById('jbConfigured');
    var availableDiv = document.getElementById('jbAvailable');
    var resultDiv = document.getElementById('jbResult');
    var listedJobs = [];
    var configuredJobs = [];
    var selectedJobs = {};
    var pageHasMore = false;
    var pageSize = 100;
    var nextOffset = 0;
    var configuredPageSize = 20;
    var configuredPage = 0;
    var loadingPage = false;
    var searchTimer = null;

    function esc(s) {
      return (s == null ? '' : String(s)).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    function escAttr(s) {
      return esc(s).replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function encode(s) { return encodeURIComponent(s == null ? '' : s); }

    function setStatus(text) { status.innerHTML = esc(text); }

    function selectedConnection() { return connectionInput.value || ''; }

    function resetResults() {
      configuredDiv.innerHTML = '';
      availableDiv.innerHTML = '';
      resultDiv.innerHTML = '';
      listedJobs = [];
      configuredJobs = [];
      selectedJobs = {};
      pageHasMore = false;
      nextOffset = 0;
      configuredPage = 0;
      controlsDiv.style.display = 'none';
      importBtn.disabled = true;
    }

    function updateConnectionControls() {
      var hasConnection = !!selectedConnection();
      listBtn.disabled = !hasConnection;
      searchInput.disabled = !hasConnection;
    }

    function updateImportButton() {
      importBtn.disabled = Object.keys(selectedJobs).length === 0;
    }

    function rememberSelections() {
      var boxes = document.querySelectorAll('#jbConfigured input.jbJob, #jbAvailable input.jbJob');
      for (var i = 0; i < boxes.length; i++) {
        if (boxes[i].checked) selectedJobs[boxes[i].value] = true;
        else delete selectedJobs[boxes[i].value];
      }
    }

    function jobRow(j) {
      var selectable = j.importable || j.isMultibranch;
      var note = j.alreadyImported ? ' <span class="grayNote">(refresh parameters)</span>'
        : (j.isMultibranch ? ' <span class="grayNote">(multibranch pipeline)</span>'
        : (!j.importable ? ' <span class="grayNote">(folder)</span>' : ''));
      return '<tr>'
        + '<td><input type="checkbox" class="jbJob" value="' + escAttr(j.fullName) + '"'
        + (selectable ? '' : ' disabled="disabled"') + '/></td>'
        + '<td>' + esc(j.fullName) + note + '</td>'
        + '<td class="grayNote" title="' + escAttr(j.type) + '">' + esc(j.displayType) + '</td>'
        + '</tr>';
    }

    function jobTable(title, jobs, emptyMessage, collapsible, totalCount) {
      if (!jobs.length) return '<p class="grayNote">' + esc(emptyMessage) + '</p>';
      var count = ' <span class="grayNote">(' + (totalCount == null ? jobs.length : totalCount) + ')</span>';
      var heading = collapsible
        ? '<details open="open"><summary><strong>' + esc(title) + count + '</strong></summary>'
        : (title ? '<h3>' + esc(title) + count + '</h3>' : '');
      var html = heading
        + '<table class="parametersTable" style="width:auto;"><tr><th></th><th>Job</th><th>Type</th></tr>';
      for (var i = 0; i < jobs.length; i++) html += jobRow(jobs[i]);
      return html + '</table>' + (collapsible ? '</details>' : '');
    }

    function renderJobs() {
      resultDiv.innerHTML = '';
      rememberSelections();
      var available = [];
      var query = (searchInput.value || '').toLowerCase().replace(/^\s+|\s+$/g, '');
      for (var i = 0; i < listedJobs.length; i++) {
        var job = listedJobs[i];
        if (!query || (job.fullName || '').toLowerCase().indexOf(query) >= 0) {
          available.push(job);
        }
      }
      configuredDiv.innerHTML = configuredJobs.length
        ? renderConfiguredJobs()
        : '<p class="grayNote">No jobs are configured in this project.</p>';
      availableDiv.innerHTML = jobTable('', available,
        query ? 'No available jobs match your search.' : 'No available jobs found at this path.');
      if (pageHasMore) {
        availableDiv.innerHTML += '<p class="grayNote">Loaded ' + listedJobs.length
          + ' jobs. More jobs are available in Jenkins.</p>'
        + '<input type="button" class="btn" id="jbLoadMoreBtn" value="Load more"/>';
        document.getElementById('jbLoadMoreBtn').onclick = function () {
          listPage(nextOffset, false);
        };
      }
      controlsDiv.style.display = listedJobs.length || configuredJobs.length ? '' : 'none';
      var boxes = document.querySelectorAll('#jbConfigured input.jbJob, #jbAvailable input.jbJob');
      for (var b = 0; b < boxes.length; b++) {
        boxes[b].checked = !!selectedJobs[boxes[b].value];
        boxes[b].onchange = function () {
          if (this.checked) selectedJobs[this.value] = true;
          else delete selectedJobs[this.value];
          updateImportButton();
        };
      }
      var previousButton = document.getElementById('jbConfiguredPrevious');
      var nextButton = document.getElementById('jbConfiguredNext');
      if (previousButton) previousButton.onclick = function () {
        rememberSelections();
        configuredPage--;
        renderJobs();
      };
      if (nextButton) nextButton.onclick = function () {
        rememberSelections();
        configuredPage++;
        renderJobs();
      };
      updateImportButton();
    }

    function renderConfiguredJobs() {
      var pageCount = Math.ceil(configuredJobs.length / configuredPageSize);
      configuredPage = Math.min(configuredPage, pageCount - 1);
      var start = configuredPage * configuredPageSize;
      var pageJobs = configuredJobs.slice(start, start + configuredPageSize);
      var html = jobTable('Already configured', pageJobs, '', true, configuredJobs.length);
      if (pageCount > 1) {
        html += '<div style="margin: 0.5em 0;">'
          + '<input type="button" class="btn" id="jbConfiguredPrevious" value="Previous"'
          + (configuredPage === 0 ? ' disabled="disabled"' : '') + '/>'
          + '<span class="smallNote" style="margin: 0 1em;">Page ' + (configuredPage + 1)
          + ' of ' + pageCount + '</span>'
          + '<input type="button" class="btn" id="jbConfiguredNext" value="Next"'
          + (configuredPage === pageCount - 1 ? ' disabled="disabled"' : '') + '/>'
          + '</div>';
      }
      return html;
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

    function listPage(offset, replace, requestedSearch) {
      if (loadingPage) return;
      if (!selectedConnection()) {
        resetResults();
        setStatus('Select a Jenkins connection first');
        return;
      }
      loadingPage = true;
      rememberSelections();
      var search = requestedSearch == null ? searchInput.value : requestedSearch;
      if (replace) {
        configuredDiv.innerHTML = '';
        availableDiv.innerHTML = '';
        resultDiv.innerHTML = '';
        searchInput.value = search;
        listedJobs = [];
        configuredJobs = [];
        configuredPage = 0;
        pageHasMore = false;
        nextOffset = 0;
        controlsDiv.style.display = 'none';
        importBtn.disabled = true;
      }
      setStatus(offset ? 'Loading more jobs…' : 'Listing jobs…');
      BS.ajaxRequest(url, {
        parameters: 'action=list&projectExternalId=' + encode(projectExternalId)
          + '&connectionId=' + encode(selectedConnection())
          + '&folderPath=' + encode(folderInput.value)
          + '&search=' + encode(search)
          + '&offset=' + encode(offset) + '&limit=' + encode(pageSize),
        onComplete: function (transport) {
          loadingPage = false;
          var data = parse(transport);
          if (!data) { setStatus('HTTP ' + transport.status + ' @ ' + url + ' — ' + (transport.responseText || '').replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').substring(0, 200)); return; }
          if (data.error) { setStatus('Error: ' + data.error); return; }
          if (replace) {
            listedJobs = data.jobs || [];
            configuredJobs = data.configuredJobs || [];
          }
          else listedJobs = listedJobs.concat(data.jobs || []);
          pageHasMore = !!data.hasMore;
          nextOffset = data.nextOffset == null ? offset + (data.jobs || []).length : data.nextOffset;
          setStatus('');
          renderJobs();
        }
      });
    }

    listBtn.onclick = function () {
      resetResults();
      resultDiv.innerHTML = '';
      searchInput.value = '';
      listPage(0, true, '');
    };

    connectionInput.onchange = function () {
      resetResults();
      updateConnectionControls();
      setStatus(selectedConnection() ? 'Connection selected. Click List jobs.' : '');
    };

    updateConnectionControls();

    searchInput.oninput = function () {
      clearTimeout(searchTimer);
      searchTimer = setTimeout(function () {
        listPage(0, true, searchInput.value);
      }, 350);
    };

    importBtn.onclick = function () {
      if (!selectedConnection()) { setStatus('Select a Jenkins connection first'); return; }
      rememberSelections();
      var jobs = Object.keys(selectedJobs);
      if (!jobs.length) { setStatus('Select at least one job'); return; }
      var params = 'action=import&projectExternalId=' + encode(projectExternalId)
        + '&connectionId=' + encode(selectedConnection());
      for (var i = 0; i < jobs.length; i++) { params += '&job=' + encode(jobs[i]); }
      setStatus('Importing…');
      importBtn.disabled = true;
      BS.ajaxRequest(url, {
        parameters: params,
        onComplete: function (transport) {
          importBtn.disabled = false;
          var data = parse(transport);
          if (!data) { setStatus('HTTP ' + transport.status + ' @ ' + url + ' — ' + (transport.responseText || '').replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').substring(0, 200)); return; }
          if (data.error) { setStatus('Error: ' + data.error); return; }
          setStatus('Done. Re-list to refresh the configured-jobs list.');
          renderResult(data);
        }
      });
    };

  })();
</script>
