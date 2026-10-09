(function () {
  "use strict";

  var branchParameterName = "jenkins.branch";
  var configId = "jenkinsBridgeMultibranchRunDialogConfig";
  var parameterRowClass = "jenkinsBridgeChildParameterRow";
  var parameterStatusClass = "jenkinsBridgeChildParameterStatus";

  function buildTypeId(dialog) {
    var field = dialog.querySelector("#runCustomBuildHidden input[name='buildTypeId']");
    return field ? field.value : "";
  }

  function branchField(dialog) {
    var parameterNames = dialog.querySelectorAll(
      "#properties-tab input[name='customParameterNames']"
    );
    for (var i = 0; i < parameterNames.length; i++) {
      if (parameterNames[i].value === branchParameterName) {
        var row = parameterNames[i].closest("tr");
        return row ? row.querySelector(".paramValue input, .paramValue textarea") : null;
      }
    }
    return null;
  }

  function selectorFor(field, dialog, config) {
    var select = document.createElement("select");
    select.name = field.name;
    select.id = field.id;
    select.className = field.className;
    select.setAttribute("aria-label", "Jenkins branch or tag");
    select.setAttribute("data-jenkins-bridge-branch-select", "true");

    var placeholder = document.createElement("option");
    placeholder.value = "";
    placeholder.textContent = "Select a Jenkins branch or tag";
    select.appendChild(placeholder);

    select.addEventListener("change", function () {
      loadChildParameters(dialog, select, config);
    });
    field.parentNode.replaceChild(select, field);
    return select;
  }

  function showBranchError(container, message, retry) {
    var status = document.createElement("span");
    status.className = "jenkinsBridgeBranchSelectorError errorMessage";
    status.setAttribute("role", "alert");
    status.textContent = message;
    container.appendChild(status);

    var button = document.createElement("button");
    button.type = "button";
    button.className = "btn btn_mini jenkinsBridgeBranchSelectorRetry";
    button.textContent = "Retry";
    button.addEventListener("click", function () {
      status.remove();
      button.remove();
      retry();
    });
    container.appendChild(button);
  }

  function childrenUrl(config, dialog, childJob) {
    var url = config.getAttribute("data-children-url")
      + "?buildTypeId=" + encodeURIComponent(buildTypeId(dialog));
    if (childJob) {
      url += "&childJob=" + encodeURIComponent(childJob);
    }
    return url;
  }

  function loadChildren(dialog, select, oldValue, config) {
    select.disabled = true;
    select.options[0].textContent = "Loading Jenkins branches…";

    fetch(childrenUrl(config, dialog), { credentials: "same-origin", headers: { Accept: "application/json" } })
      .then(function (response) {
        return response.json().then(function (body) {
          if (!response.ok) {
            throw new Error(body.error || "Jenkins branches could not be loaded");
          }
          return body;
        });
      })
      .then(function (children) {
        select.options[0].textContent = children.length
          ? "Select a Jenkins branch or tag"
          : "No runnable Jenkins branches or tags found";
        children.forEach(function (child) {
          var option = document.createElement("option");
          option.value = child.fullName;
          option.textContent = child.name;
          if (oldValue === child.fullName || oldValue === child.name) {
            option.selected = true;
          }
          select.appendChild(option);
        });
        select.disabled = children.length === 0;
        if (select.value) {
          loadChildParameters(dialog, select, config);
        }
      })
      .catch(function (error) {
        select.options[0].textContent = "Could not load Jenkins branches";
        showBranchError(select.parentNode, error.message, function () {
          var replacement = selectorFor(select, dialog, config);
          loadChildren(dialog, replacement, oldValue, config);
        });
      });
  }

  function removeChildParameterRows(dialog) {
    var rows = dialog.querySelectorAll("." + parameterRowClass + ", ." + parameterStatusClass);
    for (var i = 0; i < rows.length; i++) {
      rows[i].remove();
    }
  }

  function showParameterStatus(select, message, isError, retry) {
    var branchRow = select.closest("tr");
    if (!branchRow) {
      return;
    }
    var row = document.createElement("tr");
    row.className = parameterStatusClass;
    var cell = document.createElement("td");
    cell.colSpan = 3;
    var text = document.createElement("span");
    text.className = isError ? "errorMessage" : "smallNote";
    text.textContent = message;
    cell.appendChild(text);
    if (retry) {
      var button = document.createElement("button");
      button.type = "button";
      button.className = "btn btn_mini jenkinsBridgeChildParameterRetry";
      button.textContent = "Retry";
      button.addEventListener("click", retry);
      cell.appendChild(button);
    }
    row.appendChild(cell);
    branchRow.parentNode.insertBefore(row, branchRow.nextSibling);
  }

  function existingParameterNames(dialog) {
    var names = {};
    var fields = dialog.querySelectorAll(
      "#properties-tab input[name='customParameterNames']"
    );
    for (var i = 0; i < fields.length; i++) {
      if (fields[i].value) {
        names[fields[i].value] = true;
      }
    }
    return names;
  }

  function teamCityParameterId(name) {
    var trimmed = name.replace(/^\s+|\s+$/g, "");
    var escapedCharacters = "!\"#$%&'()*+,-./:;<=>?@[\\]^`{|}~";
    var identifier = "";
    var hash = 0;

    for (var i = 0; i < trimmed.length; i++) {
      var character = trimmed.charAt(i);
      var code = trimmed.charCodeAt(i);
      hash = (Math.imul(31, hash) + code) | 0;
      if (/\s/.test(character) || escapedCharacters.indexOf(character) >= 0
          || (code >= 0 && code <= 8) || (code >= 14 && code <= 27)
          || (code >= 127 && code <= 159)) {
        identifier += "_";
      } else {
        identifier += character;
      }
    }
    if (!identifier) {
      identifier = "_empty";
    } else if (/^[0-9]/.test(identifier)) {
      identifier = "_" + identifier;
    }

    var absoluteHash = hash === -2147483648 ? hash : Math.abs(hash);
    return "parameter_" + identifier + "_" + absoluteHash;
  }

  function addField(row, parameter, id) {
    var choices = parameter.choices || [];
    var control;
    if (choices.length > 0 || parameter.type.indexOf("Boolean") >= 0) {
      control = document.createElement("select");
      if (choices.length > 0) {
        choices.forEach(function (choice) {
          var option = document.createElement("option");
          option.value = choice;
          option.textContent = choice;
          control.appendChild(option);
        });
      } else {
        ["true", "false"].forEach(function (value) {
          var option = document.createElement("option");
          option.value = value;
          option.textContent = value;
          control.appendChild(option);
        });
      }
      var desiredValue = parameter.defaultValue;
      if (choices.length > 0 && choices.indexOf(desiredValue) < 0) {
        desiredValue = choices[0];
      }
      if (desiredValue !== null && desiredValue !== undefined) {
        control.value = desiredValue;
      }
    } else if (parameter.type.indexOf("TextParameterDefinition") >= 0) {
      control = document.createElement("textarea");
      control.rows = 4;
      control.value = parameter.defaultValue || "";
    } else {
      control = document.createElement("input");
      control.type = "text";
      control.value = parameter.defaultValue || "";
    }

    control.name = id;
    control.id = id;
    control.className = "buildTypeParams";
    control.style.width = "100%";
    row.querySelector(".completionIconWrapper").appendChild(control);
  }

  function parameterRow(parameter) {
    var id = teamCityParameterId(parameter.name);
    var row = document.createElement("tr");
    row.className = parameterRowClass;

    var nameCell = document.createElement("td");
    nameCell.className = "paramName";
    var label = document.createElement("label");
    label.className = "paramName__label";
    label.htmlFor = id;
    label.title = "parameter name: " + parameter.name;
    label.textContent = parameter.name;
    nameCell.appendChild(label);

    var marker = document.createElement("input");
    marker.type = "hidden";
    marker.name = "marker_" + id;
    marker.value = parameter.name;
    nameCell.appendChild(marker);

    var customName = document.createElement("input");
    customName.type = "hidden";
    customName.name = "customParameterNames";
    customName.value = parameter.name;
    nameCell.appendChild(customName);

    var valueCell = document.createElement("td");
    valueCell.className = "paramValue";
    var wrapper = document.createElement("div");
    wrapper.className = "completionIconWrapper";
    valueCell.appendChild(wrapper);
    row.appendChild(nameCell);
    row.appendChild(valueCell);
    var editCell = document.createElement("td");
    editCell.className = "edit";
    row.appendChild(editCell);
    addField(row, parameter, id);
    return row;
  }

  function renderChildParameters(dialog, select, parameters) {
    var names = existingParameterNames(dialog);
    var branchRow = select.closest("tr");
    var insertAfter = branchRow;
    var rendered = 0;
    parameters.forEach(function (parameter) {
      if (!parameter.name || names[parameter.name]) {
        return;
      }
      var row = parameterRow(parameter);
      insertAfter.parentNode.insertBefore(row, insertAfter.nextSibling);
      insertAfter = row;
      names[parameter.name] = true;
      rendered++;
    });
    if (rendered === 0) {
      showParameterStatus(select, "This Jenkins branch has no additional build parameters.", false);
    }
  }

  function loadChildParameters(dialog, select, config) {
    var childJob = select.value;
    select._jenkinsBridgeParameterRequest = (select._jenkinsBridgeParameterRequest || 0) + 1;
    var requestId = select._jenkinsBridgeParameterRequest;
    removeChildParameterRows(dialog);
    if (!childJob) {
      return;
    }

    showParameterStatus(select, "Loading Jenkins build parameters…", false);
    fetch(childrenUrl(config, dialog, childJob), {
      credentials: "same-origin",
      headers: { Accept: "application/json" }
    })
      .then(function (response) {
        return response.json().then(function (body) {
          if (!response.ok) {
            throw new Error(body.error || "Jenkins build parameters could not be loaded");
          }
          return body;
        });
      })
      .then(function (parameters) {
        if (select._jenkinsBridgeParameterRequest !== requestId || select.value !== childJob) {
          return;
        }
        removeChildParameterRows(dialog);
        renderChildParameters(dialog, select, parameters);
      })
      .catch(function (error) {
        if (select._jenkinsBridgeParameterRequest !== requestId || select.value !== childJob) {
          return;
        }
        removeChildParameterRows(dialog);
        showParameterStatus(select, error.message, true, function () {
          loadChildParameters(dialog, select, config);
        });
      });
  }

  function enhanceDialog() {
    var dialog = document.getElementById("runBuild");
    var config = document.getElementById(configId);
    if (!dialog || !config || !buildTypeId(dialog)) {
      return;
    }

    var field = branchField(dialog);
    if (!field) {
      return;
    }
    var oldValue = field.value;
    var select = selectorFor(field, dialog, config);
    loadChildren(dialog, select, oldValue, config);
  }

  function start() {
    enhanceDialog();
    var observer = new MutationObserver(enhanceDialog);
    observer.observe(document.documentElement, { childList: true, subtree: true });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
