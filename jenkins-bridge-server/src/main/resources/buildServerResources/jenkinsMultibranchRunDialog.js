(function () {
  "use strict";

  var parameterName = "jenkins.branch";
  var configId = "jenkinsBridgeMultibranchRunDialogConfig";

  function buildTypeId(dialog) {
    var field = dialog.querySelector("#runCustomBuildHidden input[name='buildTypeId']");
    return field ? field.value : "";
  }

  function branchField(dialog) {
    var parameterNames = dialog.querySelectorAll(
      "#properties-tab input[name='customParameterNames']"
    );
    for (var i = 0; i < parameterNames.length; i++) {
      if (parameterNames[i].value === parameterName) {
        var row = parameterNames[i].closest("tr");
        return row ? row.querySelector(".paramValue input, .paramValue textarea") : null;
      }
    }
    return null;
  }

  function selectorFor(field) {
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

    field.parentNode.replaceChild(select, field);
    return select;
  }

  function showError(container, message, retry) {
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

  function loadChildren(dialog, select, oldValue, config) {
    select.disabled = true;
    select.options[0].textContent = "Loading Jenkins branches…";

    var url = config.getAttribute("data-children-url")
      + "?buildTypeId=" + encodeURIComponent(buildTypeId(dialog));
    fetch(url, { credentials: "same-origin", headers: { Accept: "application/json" } })
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
      })
      .catch(function (error) {
        select.options[0].textContent = "Could not load Jenkins branches";
        showError(select.parentNode, error.message, function () {
          var oldSelector = select;
          var replacement = selectorFor(oldSelector);
          loadChildren(dialog, replacement, oldValue, config);
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
    var select = selectorFor(field);
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
