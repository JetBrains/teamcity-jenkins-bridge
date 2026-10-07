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
    var STAGE_COLUMN_WIDTH = 210, STAGE_ROW_HEIGHT = 66;
    var STAGE_CARD_WIDTH = 172, STAGE_CARD_HEIGHT = 40, GRAPH_PADDING = 16;
    var PARALLEL_FORK_GAP = 14, PARALLEL_GROUP_HEADER_SPACE = 36;
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
    function getLongestPathDepth(node, nodesById, memoizedDepths, recursionStack) {
      if (memoizedDepths[node.id] != null) return memoizedDepths[node.id];
      if (recursionStack[node.id]) return 0;
      recursionStack[node.id] = true;
      var depth = 0;
      for (var i = 0; i < node.parents.length; i++) {
        var parent = nodesById[node.parents[i]];
        if (parent) {
          depth = Math.max(depth,
            1 + getLongestPathDepth(parent, nodesById, memoizedDepths, recursionStack));
        }
      }
      recursionStack[node.id] = false;
      memoizedDepths[node.id] = depth;
      return depth;
    }

    function layoutGraphNodesByLongestPath(nodes) {
      var nodesById = {}, i;
      for (i = 0; i < nodes.length; i++) nodesById[nodes[i].id] = nodes[i];
      var memoizedDepths = {}, columnsByDepth = {}, maximumDepth = 0;
      for (i = 0; i < nodes.length; i++) {
        var depth = getLongestPathDepth(nodes[i], nodesById, memoizedDepths, {});
        (columnsByDepth[depth] = columnsByDepth[depth] || []).push(nodes[i]);
        if (depth > maximumDepth) maximumDepth = depth;
      }
      var maximumRowCount = 0;
      for (var depthColumn = 0; depthColumn <= maximumDepth; depthColumn++) {
        var column = columnsByDepth[depthColumn] || [];
        for (var row = 0; row < column.length; row++) {
          column[row]._x = GRAPH_PADDING + depthColumn * STAGE_COLUMN_WIDTH;
          column[row]._y = GRAPH_PADDING + row * STAGE_ROW_HEIGHT;
        }
        if (column.length > maximumRowCount) maximumRowCount = column.length;
      }
      return {
        nodesById: nodesById,
        width: GRAPH_PADDING * 2 + (maximumDepth + 1) * STAGE_COLUMN_WIDTH - (STAGE_COLUMN_WIDTH - STAGE_CARD_WIDTH),
        height: GRAPH_PADDING * 2 + Math.max(1, maximumRowCount) * STAGE_ROW_HEIGHT - (STAGE_ROW_HEIGHT - STAGE_CARD_HEIGHT)
      };
    }

    function attachClick(group, node) {
      group.style.cursor = 'pointer';
      group.addEventListener('click', function () { selectStage(node.id, node.name); });
    }

    function createGraphSvg(width, height, arrowMarkerId) {
      var svg = el('svg', {
        width: width,
        height: height,
        style: 'font-family:inherit; font-size:12px;'
      });
      if (!arrowMarkerId) return svg;

      var definitions = el('defs', {});
      var arrowMarker = el('marker', {
        id: arrowMarkerId,
        markerWidth: '7',
        markerHeight: '7',
        refX: '6',
        refY: '3.5',
        orient: 'auto',
        markerUnits: 'strokeWidth'
      });
      arrowMarker.appendChild(el('path', { d: 'M0,0 L7,3.5 L0,7 z', fill: '#5f6368' }));
      definitions.appendChild(arrowMarker);
      svg.appendChild(definitions);
      return svg;
    }

    function addGraphPath(svg, pathData, stroke, strokeWidth, arrowMarkerId) {
      var attributes = {
        d: pathData,
        fill: 'none',
        stroke: stroke,
        'stroke-width': strokeWidth
      };
      if (arrowMarkerId) attributes['marker-end'] = 'url(#' + arrowMarkerId + ')';
      svg.appendChild(el('path', attributes));
    }

    function drawStageCard(svg, stage, leftX, topY, maximumNameLength, tooltipHint) {
      var stageColor = color(stage.status);
      var isSelected = stage.id === selectedId;
      var card = el('g', {});
      card.appendChild(el('rect', {
        x: leftX,
        y: topY,
        width: STAGE_CARD_WIDTH,
        height: STAGE_CARD_HEIGHT,
        rx: 5,
        ry: 5,
        fill: stageColor[0],
        stroke: isSelected ? '#1a73e8' : stageColor[1],
        'stroke-width': isSelected ? '3.5' : '2'
      }));

      var title = el('text', { x: leftX + 10, y: topY + 17, fill: '#202124' });
      title.appendChild(document.createTextNode(truncate(stage.name || '(stage)', maximumNameLength || 22)));
      card.appendChild(title);

      var status = el('text', {
        x: leftX + 10,
        y: topY + 32,
        fill: stageColor[1],
        'font-size': '10px'
      });
      status.appendChild(document.createTextNode(stage.status));
      card.appendChild(status);

      var tooltip = el('title', {});
      tooltip.appendChild(document.createTextNode((stage.name || '(stage)') + ' — ' + stage.status
        + (stage.synthetic ? ' — Jenkins synthetic stage' : '') + (tooltipHint || '')));
      card.appendChild(tooltip);
      attachClick(card, stage);
      svg.appendChild(card);
    }

    function drawParallelGroupLabel(svg, group, branchCount, centerX, baselineY) {
      var groupLabel = el('g', {});
      var title = (group.name || '(stage)') + ' (' + branchCount + ')';
      var label = el('text', {
        x: centerX,
        y: baselineY - STAGE_CARD_HEIGHT / 2 - 10,
        fill: group.id === selectedId ? '#1a73e8' : '#202124',
        'font-size': '14px',
        'font-weight': '500',
        'text-anchor': 'middle'
      });
      label.appendChild(document.createTextNode(truncate(title, 24)));
      groupLabel.appendChild(label);

      var tooltip = el('title', {});
      tooltip.appendChild(document.createTextNode((group.name || '(stage)') + ' — ' + group.status
        + ' — ' + branchCount + ' parallel branches'));
      groupLabel.appendChild(tooltip);
      attachClick(groupLabel, group);
      svg.appendChild(groupLabel);
    }

    function buildSequentialStageLayout(topLevelStages, childStagesByParentId) {
      var stageColumns = [];
      var mainLineY = GRAPH_PADDING + PARALLEL_GROUP_HEADER_SPACE;
      var lastBranchIndex = 0;
      var hasActiveStage = false;

      for (var i = 0; i < topLevelStages.length; i++) {
        var topLevelStage = topLevelStages[i];
        var parallelBranches = childStagesByParentId[topLevelStage.id] || [];
        var isParallelGroup = parallelBranches.length > 1;
        var x = GRAPH_PADDING + i * STAGE_COLUMN_WIDTH;

        stageColumns.push({
          node: topLevelStage,
          branches: parallelBranches,
          x: x,
          isParallelGroup: isParallelGroup,
          forkX: x - PARALLEL_FORK_GAP,
          joinX: x + STAGE_CARD_WIDTH + PARALLEL_FORK_GAP
        });

        if (isParallelGroup) {
          lastBranchIndex = Math.max(lastBranchIndex, parallelBranches.length - 1);
        }
        if (ACTIVE[topLevelStage.status]) hasActiveStage = true;
        for (var branchIndex = 0; branchIndex < parallelBranches.length; branchIndex++) {
          if (ACTIVE[parallelBranches[branchIndex].status]) hasActiveStage = true;
        }
      }

      var columnCount = Math.max(1, stageColumns.length);
      return {
        stageColumns: stageColumns,
        mainLineY: mainLineY,
        width: GRAPH_PADDING * 2 + (columnCount - 1) * STAGE_COLUMN_WIDTH + STAGE_CARD_WIDTH,
        height: mainLineY + lastBranchIndex * STAGE_ROW_HEIGHT + STAGE_CARD_HEIGHT / 2 + GRAPH_PADDING,
        hasActiveStage: hasActiveStage
      };
    }

    function getStageInputX(stageColumn) {
      return stageColumn.isParallelGroup ? stageColumn.forkX : stageColumn.x;
    }

    function getStageOutputX(stageColumn) {
      return stageColumn.isParallelGroup ? stageColumn.joinX : stageColumn.x + STAGE_CARD_WIDTH;
    }

    function branchCenterY(layout, branchIndex) {
      return layout.mainLineY + branchIndex * STAGE_ROW_HEIGHT;
    }

    function drawParallelGroupForkAndJoin(svg, stageColumn, layout) {
      var lastBranchY = branchCenterY(layout, stageColumn.branches.length - 1);
      addGraphPath(svg, 'M' + stageColumn.forkX + ',' + layout.mainLineY + ' V' + lastBranchY,
        '#b0bfd3', '2');
      addGraphPath(svg, 'M' + stageColumn.joinX + ',' + layout.mainLineY + ' V' + lastBranchY,
        '#b0bfd3', '2');

      for (var i = 0; i < stageColumn.branches.length; i++) {
        var y = branchCenterY(layout, i);
        addGraphPath(svg, 'M' + stageColumn.forkX + ',' + y + ' H' + stageColumn.x,
          '#b0bfd3', '2');
        addGraphPath(svg, 'M' + (stageColumn.x + STAGE_CARD_WIDTH) + ',' + y + ' H' + stageColumn.joinX,
          '#b0bfd3', '2');
      }
    }

    function drawSequentialStageConnections(svg, layout, arrowMarkerId) {
      for (var i = 0; i + 1 < layout.stageColumns.length; i++) {
        var currentStage = layout.stageColumns[i];
        var nextStage = layout.stageColumns[i + 1];
        var path = 'M' + getStageOutputX(currentStage) + ',' + layout.mainLineY
          + ' H' + getStageInputX(nextStage);
        addGraphPath(svg, path, '#5f6368', '1.8', arrowMarkerId);
      }
    }

    function updateGraphStatus(data, viewName, hasActiveStage, note) {
      graphNoteEl.textContent = note || '';
      statusEl.textContent = 'Source: ' + data.source
        + (viewName ? ' - ' + viewName : '')
        + (hasActiveStage ? ' - running...' : ' - complete');
    }

    function renderSequentialStagesWithParallelBranches(data, childStagesByParentId, topLevelStages) {
      var layout = buildSequentialStageLayout(topLevelStages, childStagesByParentId);
      var arrowMarkerId = 'jbgSequenceArrow-' + buildId;
      var svg = createGraphSvg(layout.width, layout.height, arrowMarkerId);

      // Connectors go behind stage cards so the lines stop at each card edge.
      for (var i = 0; i < layout.stageColumns.length; i++) {
        if (layout.stageColumns[i].isParallelGroup) {
          drawParallelGroupForkAndJoin(svg, layout.stageColumns[i], layout);
        }
      }
      drawSequentialStageConnections(svg, layout, arrowMarkerId);

      for (i = 0; i < layout.stageColumns.length; i++) {
        var stageColumn = layout.stageColumns[i];
        if (!stageColumn.isParallelGroup) {
          drawStageCard(svg, stageColumn.node, stageColumn.x, layout.mainLineY - STAGE_CARD_HEIGHT / 2);
          continue;
        }

        drawParallelGroupLabel(svg, stageColumn.node, stageColumn.branches.length,
          stageColumn.x + STAGE_CARD_WIDTH / 2, layout.mainLineY);
        for (var branchIndex = 0; branchIndex < stageColumn.branches.length; branchIndex++) {
          drawStageCard(svg, stageColumn.branches[branchIndex], stageColumn.x,
            branchCenterY(layout, branchIndex) - STAGE_CARD_HEIGHT / 2);
        }
      }

      graphEl.appendChild(svg);
      updateGraphStatus(data, 'parallel hierarchy', layout.hasActiveStage,
        'Parallel stages are shown as branches that rejoin before the next sequential stage.');
      return layout.hasActiveStage;
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
        return renderSequentialStagesWithParallelBranches(data, childrenById, roots);
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
        xById[node.id] = GRAPH_PADDING + xSlot * STAGE_COLUMN_WIDTH;
        yById[node.id] = GRAPH_PADDING + depth * STAGE_ROW_HEIGHT + STAGE_CARD_HEIGHT / 2;
        if (ACTIVE[node.status]) active = true;
      }

      var nextRootSlot = 0;
      for (i = 0; i < roots.length; i++) {
        var rootSpan = leafSpan(roots[i]);
        place(roots[i], 0, nextRootSlot);
        nextRootSlot += rootSpan;
      }
      leafCount = nextRootSlot;
      var width = GRAPH_PADDING * 2 + (Math.max(1, leafCount) - 1) * STAGE_COLUMN_WIDTH + STAGE_CARD_WIDTH;
      var height = GRAPH_PADDING * 2 + (maxDepth + 1) * STAGE_ROW_HEIGHT - (STAGE_ROW_HEIGHT - STAGE_CARD_HEIGHT);
      var markerId = 'jbgSequenceArrow-' + buildId;
      var svg = createGraphSvg(width, height, markerId);

      function drawContainmentConnector(parent, child) {
        var x1 = xById[parent.id] + STAGE_CARD_WIDTH / 2;
        var y1 = yById[parent.id] + STAGE_CARD_HEIGHT / 2;
        var x2 = xById[child.id] + STAGE_CARD_WIDTH / 2;
        var y2 = yById[child.id] - STAGE_CARD_HEIGHT / 2;
        var middleY = Math.round((y1 + y2) / 2);
        addGraphPath(svg, 'M' + x1 + ',' + y1 + ' V' + middleY + ' H' + x2 + ' V' + y2,
          '#9aa0a6', '1.5');
      }

      for (i = 0; i < nodes.length; i++) {
        var parentNode = byId[nodes[i].hierarchyParentId];
        if (parentNode && parentNode.id !== nodes[i].id) {
          drawContainmentConnector(parentNode, nodes[i]);
        }
      }
      for (i = 0; i + 1 < roots.length; i++) {
        var source = roots[i], target = roots[i + 1];
        var path = 'M' + (xById[source.id] + STAGE_CARD_WIDTH) + ',' + yById[source.id]
          + ' H' + xById[target.id];
        addGraphPath(svg, path, '#5f6368', '1.8', markerId);
      }

      for (i = 0; i < nodes.length; i++) {
        drawStageCard(svg, nodes[i], xById[nodes[i].id], yById[nodes[i].id] - STAGE_CARD_HEIGHT / 2);
      }

      graphEl.appendChild(svg);
      updateGraphStatus(data, 'hierarchy', active,
        'Arrows follow Jenkins top-level stage order; branch lines show stage containment.');
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
      var info = layoutGraphNodesByLongestPath(nodes);
      var active = false, i;

      var svg = createGraphSvg(info.width, info.height, null);

      // Edges first (under nodes).
      for (i = 0; i < nodes.length; i++) {
        var n = nodes[i];
        for (var j = 0; j < n.children.length; j++) {
          var ch = info.nodesById[n.children[j]];
          if (!ch) continue;
          var x1 = n._x + STAGE_CARD_WIDTH, y1 = n._y + STAGE_CARD_HEIGHT / 2;
          var x2 = ch._x, y2 = ch._y + STAGE_CARD_HEIGHT / 2;
          var path = 'M' + x1 + ',' + y1 + ' C' + (x1 + 40) + ',' + y1 + ' '
            + (x2 - 40) + ',' + y2 + ' ' + x2 + ',' + y2;
          addGraphPath(svg, path, '#9aa0a6', '1.5');
        }
      }

      // Draw cards after connectors so the lines sit behind each stage.
      for (i = 0; i < nodes.length; i++) {
        var node = nodes[i];
        if (ACTIVE[node.status]) active = true;
        drawStageCard(svg, node, node._x, node._y, 24, ' (click for log)');
      }

      graphEl.appendChild(svg);
      updateGraphStatus(data, '', active, '');
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
