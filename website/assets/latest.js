// Names the newest release under the headline. The static text stays if the request fails.
(function () {
  var link = document.querySelector("[data-latest-release]");
  if (!link || !window.fetch) return;
  fetch("https://api.github.com/repos/Glacier-Jellyfin/glacier-androidtv/releases/latest")
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (release) {
      var tag = release && release.tag_name;
      if (!tag) return;
      link.textContent = link.getAttribute("data-latest-release").replace("{v}", tag.replace(/^v/, ""));
    })
    .catch(function () {});
})();
