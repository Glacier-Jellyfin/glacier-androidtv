(function () {
  // Section clips play while on screen and pause when scrolled away. With reduced motion
  // they keep their poster and show controls instead.
  var clips = document.querySelectorAll("video.clip");
  if (!clips.length) return;
  var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (reduce || !("IntersectionObserver" in window)) {
    clips.forEach(function (clip) { clip.controls = true; });
    return;
  }
  var io = new IntersectionObserver(
    function (entries) {
      entries.forEach(function (entry) {
        var clip = entry.target;
        if (entry.isIntersecting) {
          clip.preload = "auto";
          var playing = clip.play();
          if (playing && playing.catch) playing.catch(function () { clip.controls = true; });
        } else {
          clip.pause();
        }
      });
    },
    { threshold: 0.5 }
  );
  clips.forEach(function (clip) { io.observe(clip); });
})();
