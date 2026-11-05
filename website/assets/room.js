// The living room: the remote switches what the TV shows, the lamp switches day and evening.
(function () {
  var root = document.documentElement;
  var stored = null;
  try { stored = localStorage.getItem("glacier-theme"); } catch (e) {}
  var dark = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;

  function apply(theme) {
    if (theme === "night") root.setAttribute("data-theme", "night");
    else root.removeAttribute("data-theme");
    document.querySelectorAll("[data-theme-toggle]").forEach(function (button) {
      button.setAttribute("aria-pressed", theme === "night" ? "true" : "false");
    });
  }
  apply(stored || (dark && dark.matches ? "night" : "day"));
  if (dark && dark.addEventListener) {
    dark.addEventListener("change", function (e) { if (!stored) apply(e.matches ? "night" : "day"); });
  }
  document.querySelectorAll("[data-theme-toggle]").forEach(function (button) {
    button.addEventListener("click", function () {
      stored = root.getAttribute("data-theme") === "night" ? "day" : "night";
      try { localStorage.setItem("glacier-theme", stored); } catch (e) {}
      apply(stored);
    });
  });

  var room = document.querySelector(".room");
  if (!room) return;
  var keys = room.querySelectorAll(".key[data-key]");
  var video = room.querySelector(".screen video");
  var picture = room.querySelector(".screen img");
  var label = room.querySelector(".note-key");
  var text = room.querySelector(".note-text");
  var receiver = room.querySelector(".receiver");
  var still = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  function press(key) {
    keys.forEach(function (k) { k.setAttribute("aria-pressed", k === key ? "true" : "false"); });
    label.textContent = key.getAttribute("data-label");
    text.textContent = key.getAttribute("data-text");
    receiver.textContent = key.getAttribute("data-receiver");
    var src = key.getAttribute("data-img");
    if (!src) {
      picture.hidden = true;
      video.hidden = false;
      if (!still && video.play) video.play().catch(function () {});
      return;
    }
    var next = new Image();
    next.onload = function () {
      picture.src = src;
      picture.alt = key.getAttribute("data-alt") || "";
      picture.hidden = false;
      video.hidden = true;
      if (video.pause) video.pause();
    };
    next.src = src;
  }

  keys.forEach(function (key) {
    key.addEventListener("click", function () { press(key); });
  });

  // While the room is in view, the keyboard works like the remote, unless another control has focus.
  var map = {
    ArrowUp: "up", ArrowDown: "down", ArrowLeft: "left", ArrowRight: "right",
    Enter: "ok", " ": "play", MediaPlayPause: "play",
    Backspace: "back", Escape: "back", BrowserBack: "back", Home: "home"
  };
  var steered = null;
  document.addEventListener("focusin", function (e) { if (e.target !== steered) steered = null; });
  var inView = true;
  if (window.IntersectionObserver) {
    inView = false;
    new IntersectionObserver(function (entries) {
      var entry = entries[entries.length - 1];
      inView = entry.intersectionRatio >= 0.5 || entry.intersectionRect.height >= window.innerHeight * 0.5;
    }, { threshold: [0, 0.25, 0.5, 0.75, 1] }).observe(room);
  }
  document.addEventListener("keydown", function (e) {
    var name = map[e.key];
    if (!name || e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey) return;
    var target = e.target;
    var inRoom = room.contains(target);
    var idle = target === document.body || target === root;
    if (!inRoom && !(idle && inView)) return;
    // A button reached with Tab answers Enter and Space by itself; one the keyboard steered to does not.
    if ((e.key === "Enter" || e.key === " ") && target.tagName === "BUTTON" && target !== steered) return;
    var key = room.querySelector('.key[data-key="' + name + '"]');
    if (!key) return;
    e.preventDefault();
    steered = key;
    key.focus({ preventScroll: true });
    press(key);
  });

  if (still && video) { video.removeAttribute("autoplay"); video.pause(); }
})();
