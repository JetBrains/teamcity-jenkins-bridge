<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="bs" tagdir="/WEB-INF/tags" %>
<%--
  "Pipeline Graph" build-results tab. Polls JenkinsPipelineGraphController for the mirrored Jenkins
  build's Blue Ocean graph and renders it as a live SVG DAG (stages + parallel branches), colored by
  normalized node status. Clicking a stage shows its whole log (G3a) in the panel below, refreshed live
  while that stage runs. Mirrors what the user sees in Jenkins Blue Ocean / Pipeline Graph View.
--%>
<div class="jenkinsBridgeGraph">
  <p class="grayNote" style="margin:0 0 0.5em;">
    Jenkins Pipeline stages for this run, as mirrored by the Jenkins Bridge. Click a stage to see its
    steps and their console output. Updates live while the build runs.
  </p>
  <div id="jbgStatus" class="grayNote" style="margin:0 0 0.5em;">Loading Jenkins pipeline graph&hellip;</div>
  <div id="jbgGraph" style="overflow:auto; max-width:100%;"></div>
  <div id="jbgDetail" style="margin-top:1em;"></div>
</div>

<script type="text/javascript">
  (function () {
    var url = '${controllerUrl}';
    var buildId = '${buildId}';

    var SVGNS = 'http://www.w3.org/2000/svg';
    var COL_W = 210, ROW_H = 66, NODE_W = 172, NODE_H = 40, PAD = 16;
    var ACTIVE = { QUEUED: 1, IN_PROGRESS: 1, PAUSED_PENDING_INPUT: 1 };
    var COLORS = {
      SUCCESS:              ['#e6f4ea', '#34a853'],
      FAILURE:              ['#fce8e6', '#d93025'],
      UNSTABLE:             ['#fef7e0', '#f29900'],
      IN_PROGRESS:          ['#e8f0fe', '#1a73e8'],
      PAUSED_PENDING_INPUT: ['#fef7e0', '#f9ab00'],
      QUEUED:               ['#f1f3f4', '#9aa0a6'],
      ABORTED:              ['#e8eaed', '#5f6368'],
      NOT_EXECUTED:         ['#f1f3f4', '#bdc1c6'],
      UNKNOWN:              ['#f1f3f4', '#bdc1c6']
    };

    var statusEl = document.getElementById('jbgStatus');
    var graphEl = document.getElementById('jbgGraph');
    var detailEl = document.getElementById('jbgDetail');

    var lastData = null;     // most recent graph payload (for re-highlight on selection)
    var selectedId = null;   // currently opened stage node id
    var selectedName = '';

    function esc(s) {
      return (s == null ? '' : String(s)).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }
    function enc(s) { return encodeURIComponent(s == null ? '' : s); }
    function parse(t) { try { return JSON.parse(t.responseText); } catch (e) { return null; } }
    function color(status) { return COLORS[status] || COLORS.UNKNOWN; }
    function nodeById(data, id) {
      if (!data || !data.nodes) return null;
      for (var i = 0; i < data.nodes.length; i++) if (data.nodes[i].id === id) return data.nodes[i];
      return null;
    }
    function el(name, attrs) {
      var e = document.createElementNS(SVGNS, name);
      for (var k in attrs) { if (attrs.hasOwnProperty(k)) e.setAttribute(k, attrs[k]); }
      return e;
    }
    function truncate(s, n) { s = s || ''; return s.length > n ? s.substring(0, n - 1) + '...' : s; }

    // Longest-path depth = column. Memoized, with a cycle guard.
    function depthOf(node, byId, memo, stack) {
      if (memo[node.id] != null) return memo[node.id];
      if (stack[node.id]) return 0;
      stack[node.id] = true;
      var d = 0;
      for (var i = 0; i < node.parents.length; i++) {
        var p = byId[node.parents[i]];
        if (p) d = Math.max(d, 1 + depthOf(p, byId, memo, stack));
      }
      stack[node.id] = false;
      memo[node.id] = d;
      return d;
    }

    function layout(nodes) {
      var byId = {}, i;
      for (i = 0; i < nodes.length; i++) byId[nodes[i].id] = nodes[i];
      var memo = {}, cols = {}, maxDepth = 0;
      for (i = 0; i < nodes.length; i++) {
        var d = depthOf(nodes[i], byId, memo, {});
        (cols[d] = cols[d] || []).push(nodes[i]);
        if (d > maxDepth) maxDepth = d;
      }
      var maxRows = 0;
      for (var c = 0; c <= maxDepth; c++) {
        var col = cols[c] || [];
        for (var r = 0; r < col.length; r++) {
          col[r]._x = PAD + c * COL_W;
          col[r]._y = PAD + r * ROW_H;
        }
        if (col.length > maxRows) maxRows = col.length;
      }
      return {
        byId: byId,
        width: PAD * 2 + (maxDepth + 1) * COL_W - (COL_W - NODE_W),
        height: PAD * 2 + Math.max(1, maxRows) * ROW_H - (ROW_H - NODE_H)
      };
    }

    function attachClick(group, node) {
      group.style.cursor = 'pointer';
      group.addEventListener('click', function () { selectStage(node.id, node.name); });
    }

    function render(data) {
      lastData = data;
      graphEl.innerHTML = '';
      if (!data || !data.pipeline || !data.nodes || !data.nodes.length) {
        statusEl.innerHTML = 'No Jenkins pipeline graph is available for this build.';
        return false;
      }
      var nodes = data.nodes;
      var info = layout(nodes);
      var active = false, i;

      var svg = el('svg', { width: info.width, height: info.height,
        style: 'font-family:inherit; font-size:12px;' });

      // Edges first (under nodes).
      for (i = 0; i < nodes.length; i++) {
        var n = nodes[i];
        for (var j = 0; j < n.children.length; j++) {
          var ch = info.byId[n.children[j]];
          if (!ch) continue;
          var x1 = n._x + NODE_W, y1 = n._y + NODE_H / 2;
          var x2 = ch._x, y2 = ch._y + NODE_H / 2;
          svg.appendChild(el('path', {
            d: 'M' + x1 + ',' + y1 + ' C' + (x1 + 40) + ',' + y1 + ' ' + (x2 - 40) + ',' + y2 + ' ' + x2 + ',' + y2,
            fill: 'none', stroke: '#9aa0a6', 'stroke-width': '1.5'
          }));
        }
      }

      // Nodes on top (clickable groups).
      for (i = 0; i < nodes.length; i++) {
        var node = nodes[i];
        var col = color(node.status);
        if (ACTIVE[node.status]) active = true;
        var selected = node.id === selectedId;
        var g = el('g', {});
        g.appendChild(el('rect', {
          x: node._x, y: node._y, width: NODE_W, height: NODE_H, rx: 5, ry: 5,
          fill: col[0], stroke: selected ? '#1a73e8' : col[1], 'stroke-width': selected ? '3.5' : '2'
        }));
        var title = el('text', { x: node._x + 10, y: node._y + 17, fill: '#202124' });
        title.appendChild(document.createTextNode(truncate(node.name, 24)));
        g.appendChild(title);
        var sub = el('text', { x: node._x + 10, y: node._y + 32, fill: col[1], 'font-size': '10px' });
        sub.appendChild(document.createTextNode(node.status));
        g.appendChild(sub);
        var t = el('title', {});
        t.appendChild(document.createTextNode(node.name + ' — ' + node.status + ' (click for log)'));
        g.appendChild(t);
        attachClick(g, node);
        svg.appendChild(g);
      }

      graphEl.appendChild(svg);
      statusEl.innerHTML = esc('Source: ' + data.source + (active ? ' - running...' : ' - complete'));
      return active;
    }

    function selectStage(id, name) {
      selectedId = id;
      selectedName = name;
      if (lastData) render(lastData);   // re-highlight the chosen node
      loadStageSteps(id, name);
    }

    function note(msg) { return '<div class="grayNote">' + msg + '</div>'; }
    function fmtDur(ms) {
      if (ms < 1000) return ms + 'ms';
      var s = ms / 1000;
      if (s < 60) return (Math.round(s * 10) / 10) + 's';
      var m = Math.floor(s / 60);
      return m + 'm ' + Math.round(s % 60) + 's';
    }

    // Jenkins Pipeline Graph View uses WFAPI's parameterDescription, which is the Pipeline node's
    // stored ArgumentsAction, as the step text. It does not infer a title from console output. The
    // generic labels for echo and sh are suppressed when that argument text is available.
    function stepPresentation(step) {
      var argumentsText = step.parameterDescription || '';
      if (!argumentsText) return { text: step.name || '(step)', title: '' };
      var showKind = step.name !== 'Print Message' && step.name !== 'Shell Script';
      return { text: argumentsText, title: showKind ? step.name : '' };
    }

    // G3b: per-step breakdown for the selected stage. Each step is a clickable row that expands its log.
    function loadStageSteps(id, name) {
      detailEl.innerHTML = note('Loading steps for ' + esc(name) + '&hellip;');
      BS.ajaxRequest(url, {
        parameters: 'action=steps&buildId=' + enc(buildId) + '&nodeId=' + enc(id),
        onComplete: function (transport) {
          if (selectedId !== id) return;  // user switched stages mid-request
          var data = parse(transport);
          var header = '<h3 class="noBorder" style="margin:0 0 0.4em;">' + esc(name) + '</h3>';
          if (!data) { detailEl.innerHTML = header + note('Could not load steps (HTTP ' + transport.status + ').'); return; }
          if (data.error) { detailEl.innerHTML = header + note('Error: ' + esc(data.error)); return; }
          if (!data.steps || !data.steps.length) { detailEl.innerHTML = header + note('No steps for this stage yet.'); return; }

          var html = header;
          for (var i = 0; i < data.steps.length; i++) {
            var s = data.steps[i];
            var col = color(s.status);
            var dur = s.durationMillis > 0 ? fmtDur(s.durationMillis) : '';
            var logId = 'jbgStep_' + i;
            var presentation = stepPresentation(s);
            var stepMeta = dur;
            html += '<div style="border:1px solid #e0e0e0; border-left:4px solid ' + col[1] + '; margin:4px 0; border-radius:4px;">'
              + '<div class="jbgStepHdr" data-log="' + logId + '" style="cursor:pointer; padding:6px 10px; display:flex; justify-content:space-between; align-items:center;">'
              + '<span style="min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="' + esc(presentation.text) + '"><span style="color:' + col[1] + ';">&#9679;</span> '
              + (presentation.title ? '<span class="grayNote">' + esc(presentation.title) + '</span> ' : '') + esc(presentation.text) + '</span>'
              + '<span class="grayNote" style="font-size:11px;">' + esc(stepMeta) + '</span>'
              + '</div>'
              + '<pre id="' + logId + '" style="display:none; max-height:360px; overflow:auto; background:#f8f9fa; '
              + 'border-top:1px solid #e0e0e0; padding:8px; margin:0; white-space:pre-wrap; word-break:break-word;">'
              + (s.log ? esc(s.log) : '(no log for this step)') + '</pre>'
              + '</div>';
          }
          detailEl.innerHTML = html;

          var hdrs = detailEl.querySelectorAll('.jbgStepHdr');
          for (var h = 0; h < hdrs.length; h++) {
            hdrs[h].addEventListener('click', function () {
              var pre = document.getElementById(this.getAttribute('data-log'));
              if (pre) pre.style.display = (pre.style.display === 'none') ? 'block' : 'none';
            });
          }
        }
      });
    }

    function load() {
      BS.ajaxRequest(url, {
        parameters: 'buildId=' + enc(buildId),
        onComplete: function (transport) {
          var data = parse(transport);
          if (!data) { statusEl.innerHTML = 'Could not load pipeline graph (HTTP ' + transport.status + ').'; return; }
          if (data.error) { statusEl.innerHTML = esc('Error: ' + data.error); return; }
          var active = render(data);
          // Keep the open stage's log fresh while that stage is still running.
          if (selectedId) {
            var sel = nodeById(data, selectedId);
            if (sel && ACTIVE[sel.status]) loadStageSteps(selectedId, sel.name);
          }
          if (active) setTimeout(load, 5000);
        }
      });
    }

    load();
  })();
</script>
