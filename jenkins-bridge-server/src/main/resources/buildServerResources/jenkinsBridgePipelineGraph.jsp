<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="bs" tagdir="/WEB-INF/tags" %>
<%--
  "Pipeline Graph" build-results tab. Polls JenkinsPipelineGraphController for the mirrored Jenkins
  build's Jenkins graph and renders its live stage hierarchy or explicit DAG, colored by
  normalized node status. Clicking a stage shows its whole log (G3a) in the panel below, refreshed live
  while that stage runs. Mirrors what the user sees in Jenkins Blue Ocean / Pipeline Graph View.
--%>
<div class="jenkinsBridgeGraph">
  <p class="grayNote" style="margin:0 0 0.5em;">
    Jenkins Pipeline stages for this run, as mirrored by the Jenkins Bridge. Click a stage to see its
    steps and their console output. Updates live while the build runs.
  </p>
  <div id="jbgStatus" class="grayNote" style="margin:0 0 0.5em;">Loading Jenkins pipeline graph&hellip;</div>
  <div id="jbgGraphNote" class="grayNote" style="margin:0 0 0.5em;"></div>
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
    var graphNoteEl = document.getElementById('jbgGraphNote');
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

    function renderSequentialParallelGroups(data, childrenById, roots) {
      var active = false, maxLane = 0;
      var mainY = PAD + 36;
      var rootX = {};
      var i;

      function isParallel(root) { return childrenById[root.id].length > 1; }
      function inputX(root) { return rootX[root.id] - 14; }
      function outputX(root) { return rootX[root.id] + NODE_W + 14; }
      function laneY(index) { return mainY + index * ROW_H; }

      for (i = 0; i < roots.length; i++) {
        rootX[roots[i].id] = PAD + i * COL_W;
        var branches = childrenById[roots[i].id];
        if (branches.length > 1) maxLane = Math.max(maxLane, branches.length - 1);
        if (ACTIVE[roots[i].status]) active = true;
        for (var branchIndex = 0; branchIndex < branches.length; branchIndex++) {
          if (ACTIVE[branches[branchIndex].status]) active = true;
        }
      }

      var width = PAD * 2 + (roots.length - 1) * COL_W + NODE_W;
      var height = mainY + maxLane * ROW_H + NODE_H / 2 + PAD;
      var svg = el('svg', { width: width, height: height, style: 'font-family:inherit; font-size:12px;' });
      var markerId = 'jbgParallelArrow-' + buildId;
      var defs = el('defs', {});
      var marker = el('marker', {
        id: markerId, markerWidth: '7', markerHeight: '7', refX: '6', refY: '3.5',
        orient: 'auto', markerUnits: 'strokeWidth'
      });
      marker.appendChild(el('path', { d: 'M0,0 L7,3.5 L0,7 z', fill: '#5f6368' }));
      defs.appendChild(marker);
      svg.appendChild(defs);

      // Draw fork and join lines first so the stage cards sit on top of them.
      for (i = 0; i < roots.length; i++) {
        var root = roots[i], branches = childrenById[root.id];
        if (!isParallel(root)) continue;
        var forkX = inputX(root), joinX = outputX(root);
        var lastY = laneY(branches.length - 1);
        svg.appendChild(el('path', {
          d: 'M' + forkX + ',' + mainY + ' V' + lastY,
          fill: 'none', stroke: '#b0bfd3', 'stroke-width': '2'
        }));
        svg.appendChild(el('path', {
          d: 'M' + joinX + ',' + mainY + ' V' + lastY,
          fill: 'none', stroke: '#b0bfd3', 'stroke-width': '2'
        }));
        for (var b = 0; b < branches.length; b++) {
          var y = laneY(b);
          svg.appendChild(el('path', {
            d: 'M' + forkX + ',' + y + ' H' + rootX[root.id],
            fill: 'none', stroke: '#b0bfd3', 'stroke-width': '2'
          }));
          svg.appendChild(el('path', {
            d: 'M' + (rootX[root.id] + NODE_W) + ',' + y + ' H' + joinX,
            fill: 'none', stroke: '#b0bfd3', 'stroke-width': '2'
          }));
        }
      }

      // Roots are Jenkins' sequential top-level stages. A parallel group's input/output points
      // sit at the fork/join, so the sequence continues around its parallel branches.
      for (i = 0; i + 1 < roots.length; i++) {
        var source = roots[i], target = roots[i + 1];
        svg.appendChild(el('path', {
          d: 'M' + (isParallel(source) ? outputX(source) : rootX[source.id] + NODE_W) + ',' + mainY
            + ' H' + (isParallel(target) ? inputX(target) : rootX[target.id]),
          fill: 'none', stroke: '#5f6368', 'stroke-width': '1.8',
          'marker-end': 'url(#' + markerId + ')'
        }));
      }

      function drawNode(node, x, y) {
        var col = color(node.status);
        var g = el('g', {});
        g.appendChild(el('rect', {
          x: x, y: y - NODE_H / 2, width: NODE_W, height: NODE_H, rx: 5, ry: 5,
          fill: col[0], stroke: node.id === selectedId ? '#1a73e8' : col[1],
          'stroke-width': node.id === selectedId ? '3.5' : '2'
        }));
        var title = el('text', { x: x + 10, y: y - 3, fill: '#202124' });
        title.appendChild(document.createTextNode(truncate(node.name || '(stage)', 22)));
        g.appendChild(title);
        var sub = el('text', { x: x + 10, y: y + 13, fill: col[1], 'font-size': '10px' });
        sub.appendChild(document.createTextNode(node.status));
        g.appendChild(sub);
        var tooltip = el('title', {});
        tooltip.appendChild(document.createTextNode((node.name || '(stage)') + ' — ' + node.status
          + (node.synthetic ? ' — Jenkins synthetic stage' : '')));
        g.appendChild(tooltip);
        attachClick(g, node);
        svg.appendChild(g);
      }

      for (i = 0; i < roots.length; i++) {
        var current = roots[i], children = childrenById[current.id];
        if (isParallel(current)) {
          var groupTitle = (current.name || '(stage)') + ' (' + children.length + ')';
          var visibleGroupTitle = truncate(groupTitle, 24);
          var groupCenterX = rootX[current.id] + NODE_W / 2;
          var groupLabel = el('g', {});
          var groupText = el('text', {
            x: groupCenterX, y: mainY - NODE_H / 2 - 10,
            fill: current.id === selectedId ? '#1a73e8' : '#202124',
            'font-size': '14px', 'font-weight': '500', 'text-anchor': 'middle'
          });
          groupText.appendChild(document.createTextNode(visibleGroupTitle));
          groupLabel.appendChild(groupText);
          var groupTooltip = el('title', {});
          groupTooltip.appendChild(document.createTextNode((current.name || '(stage)') + ' — ' + current.status
            + ' — ' + children.length + ' parallel branches'));
          groupLabel.appendChild(groupTooltip);
          attachClick(groupLabel, current);
          svg.appendChild(groupLabel);
          for (var childIndex = 0; childIndex < children.length; childIndex++) {
            drawNode(children[childIndex], rootX[current.id], laneY(childIndex));
          }
        } else {
          drawNode(current, rootX[current.id], mainY);
        }
      }

      graphEl.appendChild(svg);
      graphNoteEl.textContent = 'Parallel stages are shown as branches that rejoin before the next sequential stage.';
      statusEl.textContent = 'Source: ' + data.source + ' - parallel hierarchy' + (active ? ' - running...' : ' - complete');
      return active;
    }

    function renderHierarchy(data) {
      var nodes = data.nodes, byId = {}, childrenById = {}, roots = [];
      var xById = {}, yById = {}, spanById = {};
      var active = false, maxDepth = 0, leafCount = 0;
      var i;
      for (i = 0; i < nodes.length; i++) {
        byId[nodes[i].id] = nodes[i];
        childrenById[nodes[i].id] = [];
      }
      for (i = 0; i < nodes.length; i++) {
        var parentId = nodes[i].hierarchyParentId;
        if (parentId && byId[parentId] && parentId !== nodes[i].id) {
          childrenById[parentId].push(nodes[i]);
        } else {
          roots.push(nodes[i]);
        }
      }

      // Blue Ocean presents a parallel stage group as sibling lanes between sequential stages.
      // Use that layout when each top-level stage is either a leaf or a flat parallel group;
      // retain the full hierarchy renderer below for deeper nested stage trees.
      var hasParallelRoot = false, flatParallelHierarchy = true;
      for (i = 0; i < roots.length; i++) {
        var rootChildren = childrenById[roots[i].id];
        if (rootChildren.length > 1) {
          hasParallelRoot = true;
          for (var nestedIndex = 0; nestedIndex < rootChildren.length; nestedIndex++) {
            if (childrenById[rootChildren[nestedIndex].id].length) flatParallelHierarchy = false;
          }
        } else if (rootChildren.length === 1) {
          flatParallelHierarchy = false;
        }
      }
      if (hasParallelRoot && flatParallelHierarchy) {
        return renderSequentialParallelGroups(data, childrenById, roots);
      }

      function leafSpan(node) {
        if (spanById[node.id] != null) return spanById[node.id];
        var children = childrenById[node.id] || [];
        var span = 0;
        if (!children.length) {
          span = 1;
        } else {
          for (var childIndex = 0; childIndex < children.length; childIndex++) {
            span += leafSpan(children[childIndex]);
          }
        }
        spanById[node.id] = span;
        return span;
      }

      function place(node, depth, firstSlot) {
        maxDepth = Math.max(maxDepth, depth);
        var children = childrenById[node.id] || [];
        var xSlot;
        if (!children.length) {
          xSlot = firstSlot;
        } else {
          var nextSlot = firstSlot;
          for (var childIndex = 0; childIndex < children.length; childIndex++) {
            place(children[childIndex], depth + 1, nextSlot);
            nextSlot += leafSpan(children[childIndex]);
          }
          xSlot = (firstSlot + nextSlot - 1) / 2;
        }
        xById[node.id] = PAD + xSlot * COL_W;
        yById[node.id] = PAD + depth * ROW_H + NODE_H / 2;
        if (ACTIVE[node.status]) active = true;
      }

      var nextRootSlot = 0;
      for (i = 0; i < roots.length; i++) {
        var rootSpan = leafSpan(roots[i]);
        place(roots[i], 0, nextRootSlot);
        nextRootSlot += rootSpan;
      }
      leafCount = nextRootSlot;
      var width = PAD * 2 + (Math.max(1, leafCount) - 1) * COL_W + NODE_W;
      var height = PAD * 2 + (maxDepth + 1) * ROW_H - (ROW_H - NODE_H);
      var svg = el('svg', { width: width, height: height, style: 'font-family:inherit; font-size:12px;' });

      var markerId = 'jbgSequenceArrow-' + buildId;
      var defs = el('defs', {});
      var marker = el('marker', {
        id: markerId, markerWidth: '7', markerHeight: '7', refX: '6', refY: '3.5',
        orient: 'auto', markerUnits: 'strokeWidth'
      });
      marker.appendChild(el('path', { d: 'M0,0 L7,3.5 L0,7 z', fill: '#5f6368' }));
      defs.appendChild(marker);
      svg.appendChild(defs);

      function drawContainmentConnector(parent, child) {
        var x1 = xById[parent.id] + NODE_W / 2;
        var y1 = yById[parent.id] + NODE_H / 2;
        var x2 = xById[child.id] + NODE_W / 2;
        var y2 = yById[child.id] - NODE_H / 2;
        var middleY = Math.round((y1 + y2) / 2);
        svg.appendChild(el('path', {
          d: 'M' + x1 + ',' + y1 + ' V' + middleY + ' H' + x2 + ' V' + y2,
          fill: 'none', stroke: '#9aa0a6', 'stroke-width': '1.5'
        }));
      }

      for (i = 0; i < nodes.length; i++) {
        var parentNode = byId[nodes[i].hierarchyParentId];
        if (parentNode && parentNode.id !== nodes[i].id) {
          drawContainmentConnector(parentNode, nodes[i]);
        }
      }
      for (i = 0; i + 1 < roots.length; i++) {
        var source = roots[i], target = roots[i + 1];
        svg.appendChild(el('path', {
          d: 'M' + (xById[source.id] + NODE_W) + ',' + yById[source.id]
            + ' H' + xById[target.id],
          fill: 'none', stroke: '#5f6368', 'stroke-width': '1.8',
          'marker-end': 'url(#' + markerId + ')'
        }));
      }

      function drawNode(node) {
        var x = xById[node.id], y = yById[node.id];
        var col = color(node.status);
        var g = el('g', {});
        g.appendChild(el('rect', {
          x: x, y: y - NODE_H / 2, width: NODE_W, height: NODE_H, rx: 5, ry: 5,
          fill: col[0], stroke: node.id === selectedId ? '#1a73e8' : col[1],
          'stroke-width': node.id === selectedId ? '3.5' : '2'
        }));
        var title = el('text', { x: x + 10, y: y - 3, fill: '#202124' });
        title.appendChild(document.createTextNode(truncate(node.name || '(stage)', 22)));
        g.appendChild(title);
        var sub = el('text', { x: x + 10, y: y + 13, fill: col[1], 'font-size': '10px' });
        sub.appendChild(document.createTextNode(node.status));
        g.appendChild(sub);
        var t = el('title', {});
        t.appendChild(document.createTextNode(node.name + ' — ' + node.status
          + (node.synthetic ? ' — Jenkins synthetic stage' : '')));
        g.appendChild(t);
        attachClick(g, node);
        g.style.cursor = 'pointer';
        svg.appendChild(g);
      }

      for (i = 0; i < nodes.length; i++) {
        drawNode(nodes[i]);
      }

      graphEl.appendChild(svg);
      graphNoteEl.textContent = 'Arrows follow Jenkins top-level stage order; branch lines show stage containment.';
      statusEl.textContent = 'Source: ' + data.source + ' - hierarchy' + (active ? ' - running...' : ' - complete');
      return active;
    }

    function render(data) {
      lastData = data;
      graphEl.innerHTML = '';
      graphNoteEl.textContent = '';
      if (!data || !data.pipeline || !data.nodes || !data.nodes.length) {
        statusEl.innerHTML = 'No Jenkins pipeline graph is available for this build.';
        return false;
      }
      if (data.source === 'PIPELINE_GRAPH_VIEW') return renderHierarchy(data);
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
          if (active || (data.source === 'PIPELINE_GRAPH_VIEW' && !data.complete)) setTimeout(load, 5000);
        }
      });
    }

    load();
  })();
</script>
