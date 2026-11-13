// Shows the star count in the star button. Without it, the button shows no number.
(function () {
  var count = document.querySelector("[data-stars]");
  if (!count || !window.fetch) return;
  fetch("https://api.github.com/repos/Glacier-Jellyfin/glacier-androidtv")
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (repo) {
      var n = repo && repo.stargazers_count;
      if (typeof n !== "number" || n < 1) return;
      var lang = document.documentElement.lang || undefined;
      try {
        count.textContent = n >= 1000
          ? new Intl.NumberFormat(lang, { notation: "compact", maximumFractionDigits: 1 }).format(n)
          : n.toLocaleString(lang);
      } catch (e) {
        count.textContent = String(n);
      }
      count.hidden = false;
    })
    .catch(function () {});
})();
