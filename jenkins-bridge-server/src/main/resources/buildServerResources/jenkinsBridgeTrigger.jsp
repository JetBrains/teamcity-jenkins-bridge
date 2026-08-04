<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="bs" tagdir="/WEB-INF/tags" %>
<%--
  "Trigger Jenkins" build-configuration tab. Loads the mapped Jenkins job's build parameters and
  triggers a build via buildWithParameters. Uses BS.ajaxRequest so TeamCity's CSRF token is attached.
--%>
<div class="jenkinsBridgeTrigger">
  <h2 class="noBorder">Trigger Jenkins build</h2>
  <p class="grayNote">
    Triggers the Jenkins job mapped to this build configuration using the globally configured Jenkins
    connection. The resulting build is mirrored back into TeamCity by the bridge poller.
  </p>

  <table class="runnerFormTable">
    <tr>
      <th>Jenkins job:</th>
      <td><code id="jbtJob"></code></td>
    </tr>
  </table>

  <h3 class="noBorder" style="margin-top: 1em;">Jenkins build parameters</h3>
  <p class="grayNote">Sent to the Jenkins job when you trigger a build.</p>
  <div id="jbtParams" style="margin: 0.5em 0;"></div>

  <h3 class="noBorder" style="margin-top: 1em;">TeamCity configuration parameters</h3>
  <p class="grayNote">Applied to the TeamCity build; not sent to Jenkins.</p>
  <div id="jbtConfigParams" style="margin: 0.5em 0;"></div>

  <div style="margin: 0.5em 0;">
    <input type="button" class="btn" id="jbtReloadBtn" value="Reload parameters"/>
    <input type="button" class="btn btn_primary" id="jbtTriggerBtn" value="Trigger Jenkins build" disabled="disabled"/>
    <span id="jbtStatus" class="grayNote" style="margin-left: 1em;"></span>
  </div>

  <div id="jbtResult" style="margin-top: 1em;"></div>
</div>

<script type="text/javascript">
  (function () {
    var url = '${controllerUrl}';
    var buildTypeExternalId = '${buildTypeExternalId}';
    var jenkinsJob = '${jenkinsJob}';

    var jobEl = document.getElementById('jbtJob');
    var paramsDiv = document.getElementById('jbtParams');
    var configParamsDiv = document.getElementById('jbtConfigParams');
    var reloadBtn = document.getElementById('jbtReloadBtn');
    var triggerBtn = document.getElementById('jbtTriggerBtn');
    var status = document.getElementById('jbtStatus');
    var resultDiv = document.getElementById('jbtResult');

    var currentParams = [];

    jobEl.innerHTML = esc(jenkinsJob);

    function esc(s) {
      return (s == null ? '' : String(s)).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }
    function encode(s) { return encodeURIComponent(s == null ? '' : s); }
    function setStatus(text) { status.innerHTML = esc(text); }
    function parse(transport) { try { return JSON.parse(transport.responseText); } catch (e) { return null; } }

    function fieldId(name) { return 'jbtp_' + name; }

    function renderParams(params) {
      currentParams = params || [];
      if (!currentParams.length) {
        paramsDiv.innerHTML = '<p class="grayNote">This job has no build parameters; triggering runs it directly.</p>';
        triggerBtn.disabled = false;
        return;
      }
      var html = '<table class="runnerFormTable">';
      for (var i = 0; i < currentParams.length; i++) {
        var p = currentParams[i];
        var id = esc(fieldId(p.name));
        var field;
        if (/Boolean/.test(p.type)) {
          var checked = String(p.defaultValue) === 'true' ? ' checked="checked"' : '';
          field = '<input type="checkbox" id="' + id + '"' + checked + '/>';
        } else if (p.choices && p.choices.length) {
          field = '<select id="' + id + '">';
          for (var c = 0; c < p.choices.length; c++) {
            var sel = p.choices[c] === p.defaultValue ? ' selected="selected"' : '';
            field += '<option value="' + esc(p.choices[c]) + '"' + sel + '>' + esc(p.choices[c]) + '</option>';
          }
          field += '</select>';
        } else {
          field = '<input type="text" class="longField" id="' + id + '" value="' + esc(p.defaultValue) + '"/>';
        }
        html += '<tr><th><label for="' + id + '">' + esc(p.name) + '</label></th><td>' + field
          + ' <span class="smallNote">' + esc(p.type) + '</span></td></tr>';
      }
      html += '</table>';
      paramsDiv.innerHTML = html;
      triggerBtn.disabled = false;
    }

    function renderConfigParams(configParams) {
      var list = configParams || [];
      if (!list.length) {
        configParamsDiv.innerHTML = '<p class="grayNote">No TeamCity configuration parameters.</p>';
        return;
      }
      var html = '<table class="runnerFormTable">';
      for (var i = 0; i < list.length; i++) {
        html += '<tr><th>' + esc(list[i].name) + '</th><td><code>' + esc(list[i].value) + '</code></td></tr>';
      }
      html += '</table>';
      configParamsDiv.innerHTML = html;
    }

    function collectValues() {
      var params = '';
      for (var i = 0; i < currentParams.length; i++) {
        var p = currentParams[i];
        var el = document.getElementById(fieldId(p.name));
        var value;
        if (/Boolean/.test(p.type)) {
          value = el.checked ? 'true' : 'false';
        } else {
          value = el.value;
        }
        params += '&value_' + encode(p.name) + '=' + encode(value);
      }
      return params;
    }

    function loadParams() {
      paramsDiv.innerHTML = '';
      configParamsDiv.innerHTML = '';
      resultDiv.innerHTML = '';
      triggerBtn.disabled = true;
      setStatus('Loading parameters…');
      BS.ajaxRequest(url, {
        parameters: 'action=params&buildTypeExternalId=' + encode(buildTypeExternalId),
        onComplete: function (transport) {
          var data = parse(transport);
          if (!data) { setStatus('HTTP ' + transport.status + ' @ ' + url); return; }
          if (data.error) { setStatus('Error: ' + data.error); return; }
          setStatus('');
          renderParams(data.jenkinsParameters);
          renderConfigParams(data.configParameters);
        }
      });
    }

    reloadBtn.onclick = loadParams;

    triggerBtn.onclick = function () {
      triggerBtn.disabled = true;
      setStatus('Triggering…');
      BS.ajaxRequest(url, {
        parameters: 'action=trigger&buildTypeExternalId=' + encode(buildTypeExternalId) + collectValues(),
        onComplete: function (transport) {
          triggerBtn.disabled = false;
          var data = parse(transport);
          if (!data) { setStatus('HTTP ' + transport.status + ' @ ' + url); return; }
          if (data.error) { setStatus('Error: ' + data.error); return; }
          setStatus('Triggered.');
          var link = data.queueItemUrl
            ? '<a href="' + esc(data.queueItemUrl) + '" target="_blank">' + esc(data.queueItemUrl) + '</a>'
            : '(no queue URL returned)';
          resultDiv.innerHTML = '<div class="successMessage">Queued Jenkins build: ' + link + '</div>';
        }
      });
    };

    loadParams();
  })();
</script>
