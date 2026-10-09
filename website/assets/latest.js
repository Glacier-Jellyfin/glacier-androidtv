// Puts the newest release into the hero pill. The static text stays if the request fails.
(function () {
  var pill = document.querySelector("[data-latest-release]");
  if (!pill || !window.fetch) return;
  fetch("https://api.github.com/repos/Glacier-Jellyfin/glacier-androidtv/releases/latest")
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (release) {
      var tag = release && release.tag_name;
      if (!tag) return;
      var label = pill.querySelector("span:last-child");
      label.textContent = pill.getAttribute("data-latest-release").replace("{v}", tag.replace(/^v/, ""));
    })
    .catch(function () {});
})();
