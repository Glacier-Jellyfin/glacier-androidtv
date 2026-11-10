// The language menu, and a gentle hint when the page is not in the visitor's language.
// Without this script the menu still opens and closes (it is a <details>), and the footer links work.
(function () {
  // To add a language: one entry here, and its link in the menu and the <link rel="alternate"> of each page.
  var TEXT = {
    en: { say: "This page is also available in English.", go: "English", close: "Close" },
    de: { say: "Diese Seite gibt es auch auf Deutsch.", go: "Deutsch", close: "Schließen" },
    es: { say: "Esta página también está en español.", go: "Español", close: "Cerrar" }
  };
  var CHOSEN = "glacier-lang-chosen";
  var CLOSED = "glacier-lang-hint-closed";

  function read(key) {
    try { return sessionStorage.getItem(key) === "1"; } catch (e) { return false; }
  }
  function write(key) {
    try { sessionStorage.setItem(key, "1"); } catch (e) {}
  }

  var menu = document.querySelector("details.lang");
  if (menu) {
    // Whoever picks a language does not need a hint about another one afterwards.
    menu.querySelectorAll("a").forEach(function (link) {
      link.addEventListener("click", function () { write(CHOSEN); });
    });
    document.addEventListener("click", function (event) {
      if (menu.open && !menu.contains(event.target)) menu.open = false;
    });
    menu.addEventListener("keydown", function (event) {
      if (event.key === "Escape" && menu.open) {
        menu.open = false;
        menu.querySelector("summary").focus();
      }
    });
  }

  // The hint: the visitor's first language that this site has, if it is not the page's own.
  var here = (document.documentElement.lang || "").slice(0, 2).toLowerCase();
  var wanted = null;
  (navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language]).some(function (tag) {
    var code = String(tag || "").slice(0, 2).toLowerCase();
    if (TEXT[code]) { wanted = code; return true; }
    return false;
  });
  if (!wanted || wanted === here || read(CHOSEN) || read(CLOSED)) return;

  // The menu links are relative, so the hint works where the site is served from, too.
  var link = document.querySelector('details.lang a[hreflang="' + wanted + '"]');
  if (!link) return;

  var text = TEXT[wanted];
  var bar = document.createElement("div");
  bar.className = "lang-hint";
  bar.lang = wanted;
  bar.setAttribute("role", "region");
  bar.setAttribute("aria-label", text.go);

  var wrap = document.createElement("div");
  wrap.className = "wrap";
  var say = document.createElement("p");
  say.textContent = text.say;
  var go = document.createElement("a");
  go.className = "go";
  go.href = link.getAttribute("href");
  go.hreflang = wanted;
  go.textContent = text.go;
  go.addEventListener("click", function () { write(CHOSEN); });
  var close = document.createElement("button");
  close.type = "button";
  close.setAttribute("aria-label", text.close);
  close.textContent = "×";
  close.addEventListener("click", function () {
    write(CLOSED);
    bar.remove();
  });

  wrap.appendChild(say);
  wrap.appendChild(go);
  wrap.appendChild(close);
  bar.appendChild(wrap);
  document.body.insertBefore(bar, document.body.firstChild);
})();
