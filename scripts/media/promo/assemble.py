"""Glacier promo: UI layers that fly through 3D space and assemble into the flat app screen.

Every layer of a screen (poster card, label, nav pill, ...) becomes a thin glass slab. The slab's
front face shows the layer's PNG with an emission shader, so the colours are exactly those of the
PNG whenever the slab sits flat and front-facing (view transform "Standard", no grade). The
cinematic look comes from the slab edges/backs (lit by rim lights), glossy sheen, depth of field,
motion blur and bokeh dust, all of which fade to exactly zero on the last frames: the last
frame of a move is the flat key frame, pixel for pixel (see --diff).

Coordinate mapping: 1 px of the 1920x1080 key frame = 0.01 Blender units. The camera (40 mm,
36 mm sensor, horizontal fit) sits at z = 21.333 looking down -z at the origin, so the z=0 plane
exactly fills the frame. Elements that are layered in z at rest (nav pill above cards, ...) are
scaled/shifted by (d - z)/d so that their projection is identical to the flat layout.

Moves (30 fps, 1920x1080), output to build/promo/blender/assemble/<move>/ (preview: <move>-preview/):
  ASSEMBLE  ~5.5 s  dark space, layers fly in on curved paths in beat-locked waves and land flat
  WALL2LIB  ~8 s    opens on the lit poster wall (posterwall.py), glides along it, the posters of the
                    library's first screen detach and fly into their grid slots, the wall falls back
                    into darkness, the library chrome flies in, the last frame is the LIB key frame
  END       ~4 s    (optional) the last screen tilts back into dark space, wall posters drift in behind

Run (Blender 5.3, headless, Cycles/OptiX is set up by the script):
  blender -b --factory-startup --python scripts/media/promo/assemble.py -- --move ASSEMBLE --layout LIB
  ... -- --move ASSEMBLE --layout LIB --preview --step 6        (960x540, low samples, every 6th frame)
  ... -- --move ASSEMBLE --layout LIB --frames 100-100 --still  (one full-quality frame)
  ... -- --move WALL2LIB --layout LIB            (needs build/promo/cache/posters)
  ... -- --move ASSEMBLE --set bpm=120 --set cards_beat=2 --set card_stagger_beats=0.3

Arguments (after "--"):
  --move ASSEMBLE|WALL2LIB|WALL2LIB_LONG|LIB2LOGO    --layout NAME|DIR  (build/promo/layers/<NAME>/ with layout.json)
  --preview                       960x540, low samples, output in <move>-preview/
  --frames a-b / --step N / --still   frame range (1-based), every Nth frame, PNG named still_<n>.png
  --samples N                     override the sample count
  --engine CYCLES|EEVEE           render engine (default CYCLES, see the report for why)
  --seed N                        deterministic scatter (default 7)
  --set key=value                 override any timing parameter of PARAMS below (repeatable)
  --diff                          after rendering compare the last frame with the layout's key frame
  --suffix _de                    appended to the output folder name (per-language renders, e.g. wall2lib_de)
  --out DIR                       output root (default build/promo/blender)

Output per move: ####.png (8 bit sRGB), camera.json (per frame camera data + effect strengths),
timing.txt (seconds per frame). Timing is musical: bpm=120 -> 0.5 s per beat, 2.0 s per bar; wave
offsets are given in beats (see PARAMS).
"""
import json
import math
import os
import random
import sys
import time
import zlib

import bpy
import numpy as np
from mathutils import Matrix, Quaternion, Vector

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
LAYERS = os.path.join(REPO, "build", "promo", "layers")
SHOTS = os.path.join(REPO, "website", "assets", "screenshots")
FPS, FW, FH = 30, 1920, 1080
PX = 0.01                      # blender units per key-frame pixel
LENS, SENSOR = 40.0, 36.0
CAM_D = (FW * PX) * LENS / SENSOR   # distance at which z=0 fills the frame exactly (21.333)

# --------------------------------------------------------------------------------------
# Timing and look parameters. Times in beats (beat = 60/bpm s) unless the name says _s.
# --------------------------------------------------------------------------------------
PARAMS = dict(
    bpm=117.5,                # film music tempo (beat = 0.511 s); use --set bpm=120 for the old grid
    # ---- ASSEMBLE -----------------------------------------------------------------
    duration_s=5.5,           # whole move; the last frame is the flat key frame
    base_beat=0.0,            # base plate starts to glow in
    base_beats=4.0,           # ... and takes this long to settle
    nav_beat=4.0,             # nav pill / chrome flies in
    cards_beat=0.0,           # first poster card starts its flight
    row_beats=0.8,            # each further group (row) starts this much later
    card_stagger_beats=0.22,  # each further element within a group
    labels_lag_beats=0.4,     # text foils trail their cards
    flight_beats=4.4,         # duration of one element's flight
    cam_orbit_deg=26.0,       # start azimuth of the camera
    cam_elev_deg=8.0,
    cam_dist_start=0.68,      # start distance as fraction of the final distance
    overshoot=0.5,            # easeOutBack strength (0 = none)
    fx_frames=8,              # DoF / motion blur / sheen fade to zero over this many frames
    dof_fstop=0.12,           # f-stop at full effect (small number = strong blur, scene scale is large)
    mb_shutter=0.55,
    dust=150,
    # ---- WALL2LIB -----------------------------------------------------------------------
    w2l_duration_s=8.0,       # last frame (t = 8.0 s) is the LIB key frame = the music drop
    w2l_glide_s=3.0,          # glide along the lit wall before the posters detach
    w2l_detach_beat=6.0,      # first poster leaves the wall
    w2l_col_stagger_beats=0.22,
    w2l_row_stagger_beats=0.4,
    w2l_flight_beats=5.0,
    w2l_recede_beat=6.5,      # the rest of the wall starts to fall back ...
    w2l_recede_beats=6.0,
    w2l_recede_m=4.0,         # ... by this many metres
    w2l_lights_off_beat=7.0,  # key/wash lights and haze fade out from here ...
    w2l_lights_off_beats=3.5, # ... over this many beats
    w2l_plate_beat=10.0,      # base plate of the library lights up
    w2l_plate_beats=4.4,
    w2l_chrome_beat=12.0,     # nav pill, rail, chips fly in
    w2l_chrome_beats=3.0,
    w2l_labels_beat=12.6,     # poster titles fly in
    w2l_labels_beats=2.4,
    w2l_xf_s=0.45,            # crossfade of the hi-res posters to the exact key-frame crops ends 0.1 s before the end
    w2l_fstop=1.6,
    w2l_stage_y=4.6,          # distance of the library plane from the wall centre (m)
    w2l_block_col=10,         # wall column where the first library column sits
    w2l_dust=26,
    # WALL2LIB_LONG overrides (applied unless given with --set): 15.5 s film opening, calmer and longer
    long_overrides=dict(w2l_duration_s=15.5, w2l_glide_s=9.5, w2l_detach_beat=19.0, w2l_recede_beat=19.5,
                        w2l_lights_off_beat=20.0, w2l_lights_off_beats=4.0, w2l_plate_beat=23.5, w2l_chrome_beat=26.2,
                        w2l_dust=40),
    # ---- LIB2LOGO ------------------------------------------------------------------------
    l2l_duration_s=8.0,       # 16 beats = 4 bars at 120 BPM
    l2l_chrome_beat=0.5,      # chrome starts to dissolve (texts first, nav last)
    l2l_plate_beat=1.0,       # base plate dims ...
    l2l_plate_beats=3.0,
    l2l_detach_beat=2.0,      # first library poster leaves the grid
    l2l_col_stagger_beats=0.2,
    l2l_row_stagger_beats=0.35,
    l2l_flight_beats=4.5,
    l2l_extra_beat=2.5,       # posters from the cache arrive out of the darkness
    l2l_extra_stagger_beats=0.12,
    l2l_extra_flight_beats=4.5,
    l2l_trace_beat=9.5,       # the cyan diamond outline traces around the formed logo ...
    l2l_trace_beats=1.0,      # ... and hits at beat 10.5 (5.25 s), then a 2.75 s hold
    l2l_card=0.78,            # short side of a logo poster (BU, 100 px at the flat plane)
    l2l_gap=0.06,
    l2l_ring_n=3,             # posters per side of the pinwheel ring (12 in the ring)
    l2l_inner_n=1,            # posters per side of the inner pinwheel (4 in the inner diamond)
    l2l_center_y=1.1,        # logo centre above the middle (calm lower third for the wordmark)
    l2l_outline_margin=0.3,
    l2l_pull=0.05,            # camera pull-back while the posters fly (fraction of the flat distance) ...
    l2l_push=0.11,            # ... then a gentle push-in during the hold (relative to the pulled-back distance)
    l2l_fstop=0.12,
    l2l_dust=12,
)

# ---------------------------------------------------------------------------------------
# small math helpers
# ---------------------------------------------------------------------------------------


def clamp01(x):
    return 0.0 if x < 0.0 else 1.0 if x > 1.0 else x


def smoothstep(a, b, x):
    t = clamp01((x - a) / (b - a)) if b != a else (1.0 if x >= b else 0.0)
    return t * t * (3 - 2 * t)


def smoother(x):
    t = clamp01(x)
    return t * t * t * (t * (t * 6 - 15) + 10)


def ease_out_back(s, c1):
    """0..1 with an overshoot (c1 = 0: plain easeOutCubic). s is clamped to 0..1."""
    s = clamp01(s)
    if c1 <= 0:
        return 1 - (1 - s) ** 3
    c3 = c1 + 1.0
    return 1 + c3 * (s - 1) ** 3 + c1 * (s - 1) ** 2


def ease_out_expo(s):
    s = clamp01(s)
    return 1.0 if s >= 1 else 1 - 2 ** (-10 * s)


def ease_io(s):
    s = clamp01(s)
    return s * s * (3 - 2 * s)


def lerp(a, b, t):
    return a + (b - a) * t


def bezier(p0, c1, c2, p1, u):
    v = 1 - u
    return p0 * (v * v * v) + c1 * (3 * v * v * u) + c2 * (3 * v * u * u) + p1 * (u * u * u)


def rng_for(seed, key):
    return random.Random((seed * 1000003) ^ zlib.crc32(key.encode("utf-8")))


def qaxis(axis, angle):
    return Quaternion(axis, angle)


# ---------------------------------------------------------------------------------------
# layout loading
# ---------------------------------------------------------------------------------------


class El:
    """One layer of a screen."""

    def __init__(self, d, folder):
        self.id = d["id"]
        self.kind = d.get("kind", "card")
        self.png = os.path.join(folder, d["png"])
        self.x, self.y, self.w, self.h = d["x"], d["y"], d["w"], d["h"]
        self.z_order = d.get("z_order", 0)
        self.group = str(d.get("group", ""))
        self.title = d.get("title", "")
        self.radii = (0, 0, 0, 0)     # tl, tr, br, bl in px
        self.solid = False            # mostly opaque -> gets a thick glass slab; text is a thin foil
        self.tint = (0.3, 0.5, 0.6)
        self.zf = 0.0                 # rest z (layered)
        self.is_base = False
        self.cx = self.x + self.w / 2.0
        self.cy = self.y + self.h / 2.0
        self.box = d.get("box") or [self.x, self.y, self.x + self.w, self.y + self.h]   # nominal bounds in key-frame px
        self.parent = d.get("parent")
        self.of = d.get("of")
        self.clipped = bool(d.get("clipped_at_screen_edge"))
        self.corner_radius = d.get("corner_radius")
        self.png_use = self.png


def find_first(folder, names):
    for n in names:
        p = os.path.join(folder, n)
        if os.path.isfile(p):
            return p
    return None


def load_layout(name):
    folder = name if os.path.isabs(name) or os.path.isdir(name) else os.path.join(LAYERS, name)
    folder = os.path.abspath(folder)
    with open(os.path.join(folder, "layout.json"), encoding="utf-8") as fh:
        data = json.load(fh)
    meta = data if isinstance(data, dict) else {}
    if isinstance(data, dict):
        data = data.get("elements") or data.get("layers") or []
    els, base = [], None
    for d in data:
        e = El(d, folder)
        if e.kind == "base" or e.id in ("base", "base_plate", "plate"):
            e.is_base = True
            base = e
        else:
            els.append(e)
    if base is None:
        p = find_first(folder, ["base.png", "base_plate.png", "plate.png", "clean_base.png", "background.png"])
        if p is None:
            raise SystemExit(f"no base plate in {folder}")
        base = El(dict(id="base", kind="base", png=os.path.basename(p), x=0, y=0, w=FW, h=FH, z_order=-1000), folder)
        base.is_base = True
    key = find_first(folder, [meta.get("keyframe") or "key.png", "keyframe.png", "key.png", "key_frame.png", "frame.png", "key.jpg"])
    if meta.get("base") and os.path.isfile(os.path.join(folder, meta["base"])) and base.png != os.path.join(folder, meta["base"]):
        base.png = os.path.join(folder, meta["base"])
    els.sort(key=lambda e: e.z_order)
    # posters without their own title take it from the title text that belongs to them
    owners = {e.of: e.title for e in els if e.of and e.id.startswith("title")}
    for e in els:
        if not e.title and e.id in owners:
            e.title = owners[e.id]
    return dict(name=os.path.basename(folder), folder=folder, els=els, base=base, key=key)


def load_pixels(path, raw=False):
    """RGBA float array (h, w, 4) in file order (top row first); raw=True: file values 0..1 without colour conversion."""
    img = bpy.data.images.load(path, check_existing=not raw)
    img.colorspace_settings.name = "Non-Color" if raw else "sRGB"
    if raw:
        img.alpha_mode = "CHANNEL_PACKED"
    w, h = img.size
    arr = np.empty(w * h * 4, np.float32)
    img.pixels.foreach_get(arr)
    return arr.reshape(h, w, 4)[::-1]


def srgb_to_lin(x):
    return np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)


def linear_light_png(e, comp, cache_dir):
    """The app composites in gamma (sRGB) space, Cycles blends in linear light. For partially transparent pixels
    (anti-aliased text, rounded corners, glass edges) pick the alpha that makes the linear blend over what lies
    below equal the gamma blend, so the flat frame matches the key frame at the edges too. `comp` is the running
    composite (base plate + all lower layers, raw sRGB 0..1) and is updated in place with this layer.
    Layers clipped by the screen edge are padded to their full nominal size (the part below the frame is never
    visible once landed), so that they can fly through space as complete cards. Returns the PNG path to use."""
    os.makedirs(cache_dir, exist_ok=True)
    out = os.path.join(cache_dir, e.id + ".png")
    px = load_pixels(e.png, raw=True).copy()
    h, w = px.shape[:2]
    a = px[..., 3]
    x0, y0 = max(e.x, 0), max(e.y, 0)
    x1, y1 = min(e.x + w, FW), min(e.y + h, FH)
    if x1 > x0 and y1 > y0:
        B = comp[y0:y1, x0:x1, :3]
        sub = (slice(y0 - e.y, y1 - e.y), slice(x0 - e.x, x1 - e.x))
        C, al = px[sub][..., :3], a[sub]
        g = al[..., None] * C + (1 - al[..., None]) * B
        lB, lC, lg = srgb_to_lin(B), srgb_to_lin(C), srgb_to_lin(g)
        num = ((lg - lB) * (lC - lB)).sum(-1)
        den = ((lC - lB) ** 2).sum(-1)
        ok = (al > 0.004) & (al < 0.996) & (den > 1e-5)
        a2 = a.copy()
        a2[sub] = np.where(ok, np.clip(num / np.maximum(den, 1e-9), 0, 1), al)
        comp[y0:y1, x0:x1, :3] = g
        px[..., 3] = a2
    if e.clipped and e.y + h >= FH - 1:
        bx0, by0, bx1, by1 = e.box
        extra = int(math.ceil(by1 + 4 - (e.y + h)))
        if extra > 0:
            r_ = float(e.corner_radius or 22)
            rgb = np.repeat(px[-1:, :, :3], extra, axis=0)
            ys = (np.arange(extra) + 0.5 + (e.y + h) - by1)[:, None]         # y relative to the box bottom (<0 inside)
            xs = (np.arange(w) + 0.5 + e.x)[None, :]
            cxl = np.clip(xs, bx0 + r_, bx1 - r_)
            d = np.hypot(xs - cxl, np.clip(ys + r_, 0, None)) - r_
            al_pad = np.clip(0.5 - d, 0, 1) * (xs >= bx0) * (xs <= bx1)
            padpx = np.dstack([rgb, np.broadcast_to(al_pad.astype(np.float32), (extra, w))]).astype(np.float32)
            px = np.concatenate([px, padpx], axis=0)
            h += extra
            e.h = h
            e.cy = e.y + h / 2.0
            e.padded = True
            r2 = float(e.corner_radius or 22)
            e.radii = (e.radii[0], e.radii[1], r2, r2)
    img = bpy.data.images.new("lin_" + e.id, w, h, alpha=True, float_buffer=False)
    img.colorspace_settings.name = "Non-Color"
    img.alpha_mode = "CHANNEL_PACKED"
    img.pixels.foreach_set(np.ascontiguousarray(px[::-1]).ravel())
    img.filepath_raw = out
    img.file_format = "PNG"
    img.save()
    bpy.data.images.remove(img)
    return out


def analyse(e):
    """Estimate corner radii, solidity and tint of an element from its alpha channel."""
    px = load_pixels(e.png)
    a = px[..., 3]
    h, w = a.shape
    e.solid = bool(a.mean() > 0.55) and e.kind not in ("label", "text")
    if e.solid:
        m = a > 0.5
        rgb = px[..., :3][m]
        if len(rgb):
            e.tint = tuple(float(v) for v in rgb.mean(0))

        def corner(cx, cy, sx, sy):
            if a[cy, cx] > 0.5:
                return 0.0
            for k in range(1, min(w, h) // 2):
                if a[cy + sy * k, cx + sx * k] > 0.5:
                    return min(k * 3.41, min(w, h) / 2.0)
            return min(w, h) / 2.0

        e.radii = (corner(0, 0, 1, 1), corner(w - 1, 0, -1, 1), corner(w - 1, h - 1, -1, -1), corner(0, h - 1, 1, -1))
        if e.corner_radius:
            r_ = float(e.corner_radius)
            e.radii = (r_, r_, 0.0 if e.clipped else r_, 0.0 if e.clipped else r_)
    return e


def assign_rest_z(els, gap=0.12):
    """Rest z of every element: 0 unless it overlaps something below it (then stacked by `gap`)."""
    placed = []
    for e in sorted(els, key=lambda e: e.z_order):
        z = 0.0
        for o in placed:
            if e.x < o.x + o.w - 2 and o.x < e.x + e.w - 2 and e.y < o.y + o.h - 2 and o.y < e.y + e.h - 2:
                z = max(z, o.zf + gap)
        e.zf = z
        placed.append(e)


def rest_center(e):
    """World position of the element centre at rest, perspective-compensated so that it projects exactly."""
    k = (CAM_D - e.zf) / CAM_D
    return Vector(((e.cx - FW / 2) * PX * k, (FH / 2 - e.cy) * PX * k, e.zf)), k


# ---------------------------------------------------------------------------------------
# scene building
# ---------------------------------------------------------------------------------------
FX_NODES = []   # Value nodes holding the global "fx" (sheen, side visibility, dust) factor
SWEEP_NODES = []   # Value nodes holding the position of the light sweep band (world units along its direction)


def setup_render(a):
    sc = bpy.context.scene
    sc.render.fps = FPS
    sc.render.resolution_x, sc.render.resolution_y = FW, FH
    sc.render.resolution_percentage = 50 if a.preview else 100
    sc.render.image_settings.file_format = "PNG"
    sc.render.image_settings.color_mode = "RGB"
    sc.render.image_settings.color_depth = "8"
    sc.render.image_settings.compression = 15
    sc.render.dither_intensity = 0.0
    sc.render.film_transparent = False
    v = sc.view_settings
    v.view_transform = "Standard"
    v.look = "None"
    v.exposure = 0.0
    v.gamma = 1.0
    sc.display_settings.display_device = "sRGB"
    sc.sequencer_colorspace_settings.name = "sRGB"
    if a.engine == "CYCLES":
        sc.render.engine = "CYCLES"
        c = sc.cycles
        prefs = bpy.context.preferences.addons["cycles"].preferences
        prefs.compute_device_type = "OPTIX"
        prefs.get_devices()
        for d in prefs.devices:
            d.use = d.type != "CPU"
        c.device = "GPU"
        c.samples = a.samples or (24 if a.preview else 96)
        c.use_adaptive_sampling = True
        c.adaptive_threshold = 0.02 if a.preview else 0.006
        c.use_denoising = True
        c.denoiser = "OPTIX"
        c.max_bounces = 4
        c.diffuse_bounces = 2
        c.glossy_bounces = 3
        c.transmission_bounces = 2
        c.transparent_max_bounces = 40
        c.caustics_reflective = False
        c.caustics_refractive = False
        c.sample_clamp_indirect = 8.0
        sc.render.filter_size = 1.0
    else:
        sc.render.engine = "BLENDER_EEVEE"
        e = sc.eevee
        e.taa_render_samples = a.samples or (16 if a.preview else 64)
        sc.render.filter_size = 1.0
    sc.render.use_motion_blur = True
    sc.render.motion_blur_shutter = PARAMS["mb_shutter"]


def setup_world():
    w = bpy.data.worlds.new("Space")
    bpy.context.scene.world = w
    w.use_nodes = True
    nt = w.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputWorld")
    bg = nt.nodes.new("ShaderNodeBackground")
    # what the camera sees: deep blue-black, slightly lighter towards the middle (window space)
    tc = nt.nodes.new("ShaderNodeTexCoord")
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(tc.outputs["Window"], sep.inputs[0])
    # r = distance from the window centre
    sx = nt.nodes.new("ShaderNodeMath"); sx.operation = "SUBTRACT"; sx.inputs[1].default_value = 0.5
    sy = nt.nodes.new("ShaderNodeMath"); sy.operation = "SUBTRACT"; sy.inputs[1].default_value = 0.5
    nt.links.new(sep.outputs[0], sx.inputs[0]); nt.links.new(sep.outputs[1], sy.inputs[0])
    px_ = nt.nodes.new("ShaderNodeMath"); px_.operation = "POWER"; px_.inputs[1].default_value = 2.0
    py_ = nt.nodes.new("ShaderNodeMath"); py_.operation = "POWER"; py_.inputs[1].default_value = 2.0
    nt.links.new(sx.outputs[0], px_.inputs[0]); nt.links.new(sy.outputs[0], py_.inputs[0])
    ad = nt.nodes.new("ShaderNodeMath"); ad.operation = "ADD"
    nt.links.new(px_.outputs[0], ad.inputs[0]); nt.links.new(py_.outputs[0], ad.inputs[1])
    rr = nt.nodes.new("ShaderNodeMapRange"); rr.inputs[1].default_value = 0.0; rr.inputs[2].default_value = 0.35
    nt.links.new(ad.outputs[0], rr.inputs[0])
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].color = (0.010, 0.030, 0.048, 1)
    ramp.color_ramp.elements[1].color = (0.0006, 0.0014, 0.0030, 1)
    nt.links.new(rr.outputs[0], ramp.inputs[0])
    # what reflections/lighting see: dim cool ambient
    env = nt.nodes.new("ShaderNodeBackground")
    env.inputs[0].default_value = (0.20, 0.34, 0.48, 1)
    env.inputs[1].default_value = 0.10
    lp = nt.nodes.new("ShaderNodeLightPath")
    mix = nt.nodes.new("ShaderNodeMixShader")
    nt.links.new(ramp.outputs[0], bg.inputs[0])
    nt.links.new(lp.outputs["Is Camera Ray"], mix.inputs[0])
    nt.links.new(env.outputs[0], mix.inputs[1])
    nt.links.new(bg.outputs[0], mix.inputs[2])
    nt.links.new(mix.outputs[0], out.inputs[0])


def new_value_node(nt, name, v=1.0):
    n = nt.nodes.new("ShaderNodeValue")
    n.name = name
    n.outputs[0].default_value = v
    FX_NODES.append(n)
    return n


def make_front_material(name, png, opaque, glossy=0.30, sweep=False):
    """Emission UI shader: exact PNG colours x fade; glossy sheen x fx on top; alpha from the PNG."""
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    img = bpy.data.images.load(png, check_existing=True)
    img.alpha_mode = "STRAIGHT"
    img.colorspace_settings.name = "sRGB"
    tex = nt.nodes.new("ShaderNodeTexImage")
    tex.image = img
    tex.interpolation = "Linear"
    tex.extension = "EXTEND"
    fade = nt.nodes.new("ShaderNodeAttribute")
    fade.attribute_type = "OBJECT"
    fade.attribute_name = '["fade"]'
    em = nt.nodes.new("ShaderNodeEmission")
    nt.links.new(tex.outputs["Color"], em.inputs[0])
    nt.links.new(fade.outputs["Fac"], em.inputs[1])
    fx = new_value_node(nt, "fx")
    gl = nt.nodes.new("ShaderNodeBsdfGlossy")
    gl.inputs["Roughness"].default_value = 0.16
    gk = nt.nodes.new("ShaderNodeMath"); gk.operation = "MULTIPLY"; gk.inputs[1].default_value = glossy
    nt.links.new(fx.outputs[0], gk.inputs[0])
    nt.links.new(gk.outputs[0], gl.inputs["Color"])
    add = nt.nodes.new("ShaderNodeAddShader")
    nt.links.new(em.outputs[0], add.inputs[0])
    nt.links.new(gl.outputs[0], add.inputs[1])
    top = add
    if sweep:
        # a soft diagonal band of light that travels through world space and passes over the cards
        geo = nt.nodes.new("ShaderNodeNewGeometry")
        dot = nt.nodes.new("ShaderNodeVectorMath"); dot.operation = "DOT_PRODUCT"
        dot.inputs[1].default_value = (0.8, 0.6, 0.0)
        nt.links.new(geo.outputs["Position"], dot.inputs[0])
        sw = nt.nodes.new("ShaderNodeValue"); sw.outputs[0].default_value = -99.0
        SWEEP_NODES.append(sw)
        d1 = nt.nodes.new("ShaderNodeMath"); d1.operation = "SUBTRACT"
        nt.links.new(dot.outputs["Value"], d1.inputs[0]); nt.links.new(sw.outputs[0], d1.inputs[1])
        d2 = nt.nodes.new("ShaderNodeMath"); d2.operation = "POWER"; d2.inputs[1].default_value = 2.0
        nt.links.new(d1.outputs[0], d2.inputs[0])
        d3 = nt.nodes.new("ShaderNodeMath"); d3.operation = "MULTIPLY"; d3.inputs[1].default_value = -1.0 / (2 * 0.55 ** 2)
        nt.links.new(d2.outputs[0], d3.inputs[0])
        d4 = nt.nodes.new("ShaderNodeMath"); d4.operation = "EXPONENT"
        nt.links.new(d3.outputs[0], d4.inputs[0])
        e2 = nt.nodes.new("ShaderNodeEmission")
        e2.inputs[0].default_value = (0.75, 0.93, 1.0, 1)
        g2 = nt.nodes.new("ShaderNodeMath"); g2.operation = "MULTIPLY"; g2.inputs[1].default_value = 0.55
        nt.links.new(d4.outputs[0], g2.inputs[0])
        nt.links.new(g2.outputs[0], e2.inputs[1])
        top = nt.nodes.new("ShaderNodeAddShader")
        nt.links.new(add.outputs[0], top.inputs[0]); nt.links.new(e2.outputs[0], top.inputs[1])
    tr = nt.nodes.new("ShaderNodeBsdfTransparent")
    mix = nt.nodes.new("ShaderNodeMixShader")
    if opaque:
        alpha_src = fade.outputs["Fac"]
        mul = nt.nodes.new("ShaderNodeMath"); mul.operation = "MULTIPLY"; mul.inputs[1].default_value = 6.0
        nt.links.new(fade.outputs["Fac"], mul.inputs[0])
        mul.use_clamp = True
        nt.links.new(mul.outputs[0], mix.inputs[0])
    else:
        mul = nt.nodes.new("ShaderNodeMath"); mul.operation = "MULTIPLY"; mul.use_clamp = True
        fm = nt.nodes.new("ShaderNodeMath"); fm.operation = "MULTIPLY"; fm.inputs[1].default_value = 6.0; fm.use_clamp = True
        nt.links.new(fade.outputs["Fac"], fm.inputs[0])
        nt.links.new(tex.outputs["Alpha"], mul.inputs[0])
        nt.links.new(fm.outputs[0], mul.inputs[1])
        nt.links.new(mul.outputs[0], mix.inputs[0])
    nt.links.new(tr.outputs[0], mix.inputs[1])
    nt.links.new(top.outputs[0], mix.inputs[2])
    nt.links.new(mix.outputs[0], out.inputs[0])
    return m


def make_edge_material(name, tint, rim=1.0, body=1.0):
    """Glass edge: dark tinted metal-ish body + a bright cool rim; fully transparent when fx = 0."""
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    pb = nt.nodes.new("ShaderNodeBsdfPrincipled")
    t = Vector(tint)
    base = (0.03 + 0.25 * t.x, 0.06 + 0.30 * t.y, 0.08 + 0.35 * t.z, 1)
    pb.inputs["Base Color"].default_value = base
    pb.inputs["Base Color"].default_value = tuple(c * body for c in base[:3]) + (1,)
    pb.inputs["Metallic"].default_value = 0.8 * body
    pb.inputs["Roughness"].default_value = 0.22 if body >= 1 else 0.6
    pb.inputs["Specular IOR Level"].default_value = 0.5 * body
    em = nt.nodes.new("ShaderNodeEmission")
    em.inputs[0].default_value = (0.30 + 0.5 * t.x, 0.70 + 0.3 * t.y, 1.0, 1)
    em.inputs[1].default_value = 1.4 * rim
    lw = nt.nodes.new("ShaderNodeLayerWeight")
    lw.inputs["Blend"].default_value = 0.35
    es = nt.nodes.new("ShaderNodeMath"); es.operation = "MULTIPLY_ADD"
    es.inputs[1].default_value = 1.6; es.inputs[2].default_value = 0.35
    nt.links.new(lw.outputs["Facing"], es.inputs[0])
    gate = nt.nodes.new("ShaderNodeAttribute")
    gate.attribute_type = "OBJECT"
    gate.attribute_name = '["emit"]'
    eg = nt.nodes.new("ShaderNodeMath"); eg.operation = "MULTIPLY"
    nt.links.new(es.outputs[0], eg.inputs[0]); nt.links.new(gate.outputs["Fac"], eg.inputs[1])
    er = nt.nodes.new("ShaderNodeMath"); er.operation = "MULTIPLY"; er.inputs[1].default_value = 1.4 * rim
    nt.links.new(eg.outputs[0], er.inputs[0])
    nt.links.new(er.outputs[0], em.inputs[1])
    add = nt.nodes.new("ShaderNodeAddShader")
    nt.links.new(pb.outputs[0], add.inputs[0]); nt.links.new(em.outputs[0], add.inputs[1])
    fx = new_value_node(nt, "fx")
    fd = nt.nodes.new("ShaderNodeAttribute")
    fd.attribute_type = "OBJECT"
    fd.attribute_name = '["fade"]'
    fk = nt.nodes.new("ShaderNodeMath"); fk.operation = "MULTIPLY"; fk.inputs[1].default_value = 6.0; fk.use_clamp = True
    nt.links.new(fd.outputs["Fac"], fk.inputs[0])
    vis = nt.nodes.new("ShaderNodeMath"); vis.operation = "MULTIPLY"
    nt.links.new(fx.outputs[0], vis.inputs[0]); nt.links.new(fk.outputs[0], vis.inputs[1])
    tr = nt.nodes.new("ShaderNodeBsdfTransparent")
    mix = nt.nodes.new("ShaderNodeMixShader")
    nt.links.new(vis.outputs[0], mix.inputs[0])
    nt.links.new(tr.outputs[0], mix.inputs[1])
    nt.links.new(add.outputs[0], mix.inputs[2])
    nt.links.new(mix.outputs[0], out.inputs[0])
    return m


def make_back_material(name, tint):
    m = make_edge_material(name, tint, rim=0.0, body=0.02)
    return m


def outline(w_px, h_px, radii, e=0.6, seg=10):
    """Rounded-rectangle outline (CCW, local px, y up) with per-corner radii, grown by e px."""
    hw, hh = w_px / 2.0 + e, h_px / 2.0 + e
    tl, tr, br, bl = radii
    pts = []
    for (r, sx, sy, a0) in ((br, 1, -1, -90.0), (tr, 1, 1, 0.0), (tl, -1, 1, 90.0), (bl, -1, -1, 180.0)):
        if r < 0.5:
            pts.append((sx * hw, sy * hh))
            continue
        r = min(r, min(w_px, h_px) / 2.0)
        cx, cy = sx * (w_px / 2.0 - r), sy * (h_px / 2.0 - r)
        for i in range(seg + 1):
            ang = math.radians(a0 + 90.0 * i / seg)
            pts.append((cx + (r + e) * math.cos(ang), cy + (r + e) * math.sin(ang)))
    return pts


def make_slab(name, e, thickness, front_mat, edge_mat, back_mat):
    """Slab mesh centred on the element, front face at local z=0, extruded to z=-thickness."""
    pts = [(x, y) for x, y in outline(e.w, e.h, e.radii)] if e.solid else \
        [(-e.w / 2, -e.h / 2), (e.w / 2, -e.h / 2), (e.w / 2, e.h / 2), (-e.w / 2, e.h / 2)]
    n = len(pts)
    t = thickness if e.solid else 0.0
    sc = PX
    verts, faces, mats, uvs = [], [], [], []
    # front: centre + ring
    verts.append((0.0, 0.0, 0.0))
    for x, y in pts:
        verts.append((x * sc, y * sc, 0.0))
    for i in range(n):
        faces.append((0, 1 + i, 1 + (i + 1) % n)); mats.append(0)
    if e.solid:
        f0 = len(verts)
        for x, y in pts:
            verts.append((x * sc, y * sc, 0.0))
        b0 = len(verts)
        for x, y in pts:
            verts.append((x * sc, y * sc, -t))
        for i in range(n):
            j = (i + 1) % n
            faces.append((f0 + i, b0 + i, b0 + j, f0 + j)); mats.append(1)
        c = len(verts)
        verts.append((0.0, 0.0, -t))
        r0 = len(verts)
        for x, y in pts:
            verts.append((x * sc, y * sc, -t))
        for i in range(n):
            faces.append((c, r0 + (i + 1) % n, r0 + i)); mats.append(2)
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], faces)
    me.update()
    for p, mi in zip(me.polygons, mats):
        p.material_index = mi
        p.use_smooth = True
    uv = me.uv_layers.new(name="UV")
    for poly in me.polygons:
        for li in poly.loop_indices:
            v = me.vertices[me.loops[li].vertex_index].co
            uv.data[li].uv = ((v.x / sc + e.w / 2) / e.w, (v.y / sc + e.h / 2) / e.h)
    me.materials.append(front_mat)
    if e.solid:
        me.materials.append(edge_mat)
        me.materials.append(back_mat)
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    ob.rotation_mode = "QUATERNION"
    ob["fade"] = 1.0
    ob["emit"] = 1.0
    ob["xf"] = 1.0
    ob.visible_shadow = e.solid
    return ob


class Slab:
    def __init__(self, el, ob):
        self.el = el
        self.ob = ob
        self.pos, self.k = rest_center(el)
        self.poses = []   # per frame (loc, quat, scale, fade)


def build_slabs(lay, prefix="", base_thickness=0.10, thickness=0.06, base_zf=-0.35, hires=None, base_glossy=0.0, glossy=0.05, sweep=False):
    """Create the slab objects of one layout. Returns (base_slab, [slabs])."""
    for e in lay["els"]:
        analyse(e)
        if hires is not None and not is_card(e):
            e.solid = False          # chrome is paper thin: no side walls, no back
    assign_rest_z(lay["els"])
    slabs = []
    b = lay["base"]
    base_raw = load_pixels(b.png, raw=True)
    comp = base_raw.copy()
    for e in sorted(lay["els"], key=lambda e: e.z_order):
        e.png_use = linear_light_png(e, comp, os.path.join(lay["folder"], "_lin"))
    b.solid = True
    b.radii = (0, 0, 0, 0)
    b.zf = base_zf
    b.w, b.h, b.x, b.y = FW + 24, FH + 24, -12, -12      # bleed beyond the frame, UVs extend (EXTEND)
    b.cx, b.cy = FW / 2.0, FH / 2.0
    b.tint = (0.12, 0.30, 0.42)
    fm = make_front_material(prefix + "base_front", b.png, True, glossy=base_glossy)
    em = make_edge_material(prefix + "base_edge", b.tint, 0.08)
    bm = make_back_material(prefix + "base_back", b.tint)
    # base image is 1920x1080: map it onto the slightly larger slab by shifting uv via a mapping node
    ob = make_slab(prefix + "base", b, base_thickness, fm, em, bm)
    fix_base_uv(ob, b)
    base = Slab(b, ob)
    base.is_base = True
    for e in lay["els"]:
        opaque = False
        if hires and e.id in hires:
            fm = make_card_material(prefix + e.id + "_front", hires[e.id], e.png_use, e)
        else:
            fm = make_front_material(prefix + e.id + "_front", e.png_use, opaque, glossy=glossy, sweep=sweep)
        edge = make_edge_material(prefix + e.id + "_edge", e.tint) if e.solid else None
        back = make_back_material(prefix + e.id + "_back", e.tint) if e.solid else None
        ob = make_slab(prefix + e.id, e, thickness, fm, edge, back)
        s = Slab(e, ob)
        s.is_base = False
        slabs.append(s)
    return base, slabs


def fix_base_uv(ob, b):
    """The base slab is bigger than the 1920x1080 image: remap its UVs so the image stays 1:1."""
    me = ob.data
    uv = me.uv_layers[0]
    for poly in me.polygons:
        for li in poly.loop_indices:
            v = me.vertices[me.loops[li].vertex_index].co
            uv.data[li].uv = ((v.x / PX + FW / 2) / FW, (v.y / PX + FH / 2) / FH)


def build_dust(n, seed, zr=(-4.0, 11.0), box=((-17, 17), (-10, 10)), size=(0.02, 0.06), strength=3.5, vel=1.0, axes="xyz"):
    rnd = random.Random(seed + 99)
    m = bpy.data.materials.new("dust")
    m.use_nodes = True
    nt = m.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    em = nt.nodes.new("ShaderNodeEmission")
    oi = nt.nodes.new("ShaderNodeObjectInfo")
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].color = (0.25, 0.75, 1.0, 1)
    ramp.color_ramp.elements[1].color = (1.0, 0.85, 0.7, 1)
    nt.links.new(oi.outputs["Random"], ramp.inputs[0])
    nt.links.new(ramp.outputs[0], em.inputs[0])
    fx = new_value_node(nt, "fx")
    mul = nt.nodes.new("ShaderNodeMath"); mul.operation = "MULTIPLY"; mul.inputs[1].default_value = strength
    nt.links.new(fx.outputs[0], mul.inputs[0])
    nt.links.new(mul.outputs[0], em.inputs[1])
    nt.links.new(em.outputs[0], out.inputs[0])
    me = bpy.data.meshes.new("dustmesh")
    import bmesh
    bm = bmesh.new()
    bmesh.ops.create_icosphere(bm, subdivisions=2, radius=1.0)
    bm.to_mesh(me)
    bm.free()
    me.materials.append(m)
    dust = []
    for i in range(n):
        ob = bpy.data.objects.new(f"dust{i}", me)
        bpy.context.scene.collection.objects.link(ob)
        p3 = (rnd.uniform(*box[0]), rnd.uniform(*box[1]), rnd.uniform(*zr))
        if axes == "xzy":      # world with z up: (x, depth y, height z) given as (x, height, depth)
            p3 = (p3[0], p3[2], p3[1])
        ob.location = p3
        s = rnd.uniform(*size) * (1.0 + 2.0 * rnd.random() ** 4)
        ob.scale = (s, s, s)
        ob.visible_shadow = False
        ob.visible_glossy = False
        ob.visible_diffuse = False
        dust.append((ob, Vector((rnd.uniform(-0.35, 0.35), rnd.uniform(-0.25, 0.25), rnd.uniform(-0.6, 0.2))) * vel))
    return dust


def build_lights():
    lights = []
    for name, col, energy, size in (("key", (0.55, 0.85, 1.0), 1500.0, 14.0),
                                    ("rim", (1.0, 0.72, 0.52), 150.0, 10.0),
                                    ("top", (0.75, 0.9, 1.0), 700.0, 18.0)):
        ld = bpy.data.lights.new(name, "AREA")
        ld.color = col
        ld.energy = energy
        ld.size = size
        ld.shape = "SQUARE"
        ob = bpy.data.objects.new(name, ld)
        bpy.context.scene.collection.objects.link(ob)
        lights.append(ob)
    return lights


def aim(ob, target):
    d = Vector(target) - ob.location
    ob.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()


def make_camera():
    cd = bpy.data.cameras.new("cam")
    cd.lens = LENS
    cd.sensor_width = SENSOR
    cd.sensor_fit = "HORIZONTAL"
    cd.clip_start = 0.1
    cd.clip_end = 400.0
    cd.dof.use_dof = True
    ob = bpy.data.objects.new("cam", cd)
    bpy.context.scene.collection.objects.link(ob)
    ob.rotation_mode = "QUATERNION"
    bpy.context.scene.camera = ob
    return ob


def look_quat(pos, target, up=(0, 1, 0)):
    """Camera orientation looking from pos to target with a given world up (no roll)."""
    f = (Vector(target) - Vector(pos)).normalized()
    r = f.cross(Vector(up)).normalized()
    u = r.cross(f)
    return Matrix((r, u, -f)).transposed().to_quaternion()


# ---------------------------------------------------------------------------------------
# choreography
# ---------------------------------------------------------------------------------------


class Plan:
    """Everything a move produces: per frame poses for slabs/camera/lights/dust plus effect strengths."""

    def __init__(self, nframes):
        self.n = nframes
        self.slabs = []          # all Slab objects that take part
        self.cam = []            # per frame (loc, quat, focus_distance)
        self.fx = []             # per frame 0..1: sheen, edges, dust, DoF, motion blur
        self.light = {}          # name -> per frame location (Vector)
        self.dust = []
        self.dust_pos = []       # per dust object per frame (only start/end used)
        self.t_end = (nframes - 1) / FPS
        self.hidden = []         # slabs not to render (rest state)
        self.lens = None         # optional per frame focal length
        self.fstop = PARAMS["dof_fstop"]
        self.keyer = None        # optional callable(cam, lights) adding more keyframes
        self.per_frame = None    # optional callable(f) setting non-animated state per frame
        self.key = None          # key frame png for --diff
        self.world_up = (0, 1, 0)
        self.own_lights = False


def fx_curve(nframes, fx_frames):
    """1 during the move, ramping to 0 over `fx_frames` frames, exactly 0 on the last 2 frames."""
    out = []
    for f in range(nframes):
        r = nframes - 1 - f      # frames before the end
        out.append(1.0 - smoother((fx_frames + 2 - r) / float(fx_frames)))
    return out


def group_order(els):
    groups = {}
    for e in els:
        groups.setdefault(e.group, []).append(e)
    # groups sorted by their top-most element, then elements left to right
    order = sorted(groups, key=lambda g: min(e.cy for e in groups[g]))
    return order, groups


def cam_pose_assemble(t, P, T):
    s = clamp01(t / T)
    e = ease_io(s) * 0.5 + smoother(s) * 0.5
    th = math.radians(P["cam_orbit_deg"]) * (1 - e)
    el = math.radians(P["cam_elev_deg"]) * (1 - e)
    dist = CAM_D * lerp(P["cam_dist_start"], 1.0, e)
    tgt = Vector((1.4, -0.5, -1.5)) * (1 - e)
    pos = tgt + Vector((math.sin(th) * math.cos(el) * dist, math.sin(el) * dist, math.cos(th) * math.cos(el) * dist))
    return pos, tgt


def flight_poses(s, n, t0, D, seed, c1, zr=(1.0, 7.0), radr=(2.5, 10.0), fade_in=0.22, s0r=(0.55, 0.8), tumble=1.0):
    """Per frame (loc, quat, scale, fade) of one slab flying in from a scattered start to its rest pose.

    Stage-local coordinates (x right, y up, z towards the camera). Curved Bezier path, eased with a tiny
    overshoot, tumbling rotation that settles to identity, scale-up, brightness fade-in.
    """
    e = s.el
    r = rng_for(seed, e.id)
    F, k = s.pos, s.k
    ang = r.uniform(0, 2 * math.pi)
    rad = r.uniform(*radr) + 0.2 * abs(F.x) * (radr[1] / 13.0)
    z0 = r.uniform(*zr)
    P0 = Vector((F.x * 0.45 + math.cos(ang) * rad, F.y * 0.45 + math.sin(ang) * rad * 0.7, z0))
    sw = r.choice((-1, 1)) * r.uniform(2.5, 6.0) * (radr[1] / 13.0)
    d = F - P0
    C1 = P0 + d * 0.3 + Vector((-d.y, d.x, 0)).normalized() * sw + Vector((0, 0, r.uniform(1.0, 4.0) * (zr[1] / 8.5)))
    C2 = F + Vector((r.uniform(-1.2, 1.2), r.uniform(-1.0, 1.0), r.uniform(2.0, 3.6) * (zr[1] / 8.5)))
    axes = [(1, 0, 0), (0, 1, 0), (0, 0, 1)]
    amp = [math.radians(r.uniform(40, 100)) * r.choice((-1, 1)) * tumble,
           math.radians(r.uniform(40, 110)) * r.choice((-1, 1)) * tumble,
           math.radians(r.uniform(15, 70)) * r.choice((-1, 1)) * tumble]
    lag = [1.0, 0.9, 1.1]
    s0 = r.uniform(*s0r)
    poses = []
    for f in range(n):
        t = f / FPS
        sn = (t - t0) / D
        u = clamp01(sn)
        loc = P0 if sn < 0 else bezier(P0, C1, C2, F, ease_out_back(sn, c1))
        q = Quaternion((1, 0, 0, 0))
        for ax, am, lg in zip(axes, amp, lag):
            q = q @ qaxis(ax, am * (1 - ease_out_back(sn / lg, c1 * 0.8)))
        sc = lerp(s0, 1.0, 1 - (1 - u) ** 3) * (k if u >= 1 else lerp(1.0, k, u))
        fade = smoothstep(0.0, fade_in, sn) if sn >= 0 else 0.0
        poses.append((loc, q, sc, fade))
    return poses


def plan_assemble(lay, P, seed):
    n = int(round(P["duration_s"] * FPS)) + 1
    plan = Plan(n)
    beat = 60.0 / P["bpm"]
    base, slabs = build_slabs(lay)
    plan.slabs = [base] + slabs
    T = plan.t_end
    c1 = P["overshoot"]
    order, groups = group_order(lay["els"])
    # ---- per element schedule ------------------------------------------------------
    sched = {}
    for gi, g in enumerate(order):
        members = sorted(groups[g], key=lambda e: (e.cx, e.cy))
        for ri, e in enumerate(members):
            if e.kind in ("nav", "index", "chrome", "button", "toolbar"):
                st = P["nav_beat"] + 0.35 * ri
            else:
                st = P["cards_beat"] + gi * P["row_beats"] + ri * P["card_stagger_beats"]
            if e.kind in ("label", "text"):
                st += P["labels_lag_beats"]
            sched[e.id] = st * beat
    rest_t = T - 0.55 * beat   # all landings at least this long before the end
    for s in slabs:
        e = s.el
        t0 = sched[e.id]
        D = P["flight_beats"] * beat * rng_for(seed, e.id).uniform(0.92, 1.08)
        if t0 + D > rest_t:                       # keep the beat grid, squeeze the flight if needed
            D = max(0.8, rest_t - t0)
        s.poses = flight_poses(s, n, t0, D, seed, c1)
    # ---- base plate: glows up from the deep, settles behind the cards -----------------
    r = rng_for(seed, "base")
    bb = slabs and base
    Fb = Vector((0, 0, base.el.zf))
    P0 = Vector((2.2, -1.2, -11.0))
    C1 = Vector((1.2, -0.4, -7.0))
    C2 = Vector((-0.5, 0.2, -3.0))
    tb0, Db = P["base_beat"] * beat, P["base_beats"] * beat
    for f in range(n):
        t = f / FPS
        s_ = (t - tb0) / Db
        ep = ease_out_back(s_, c1 * 0.5)
        loc = bezier(P0, C1, C2, Fb, ep)
        q = qaxis((0, 1, 0), math.radians(-20) * (1 - ease_out_back(s_, c1 * 0.5))) @ qaxis((1, 0, 0), math.radians(8) * (1 - ease_io(s_)))
        sc = lerp(0.62, 1.0, ease_io(s_))
        fade = lerp(0.03, 1.0, smoothstep(0.0, 0.85, s_)) ** 1.4
        base.poses.append((loc, q, sc, fade))
    # ---- camera -----------------------------------------------------------------------------
    prev = None
    for f in range(n):
        t = f / FPS
        pos, tgt = cam_pose_assemble(t, P, T)
        q = look_quat(pos, tgt)
        if prev is not None and q.dot(prev) < 0:
            q = Quaternion((-q.w, -q.x, -q.y, -q.z))
        prev = q
        plan.cam.append((pos, q, (tgt - pos).length))
    plan.fx = fx_curve(n, P["fx_frames"])
    # ---- lights: slow sweeps -----------------------------------------------------------------------
    for name, a0, a1 in (("key", Vector((-15, 8, 9)), Vector((3, 11, 13))),
                         ("rim", Vector((15, -5, 5)), Vector((12, -2, 11))),
                         ("top", Vector((-2, 16, 6)), Vector((4, 15, 9)))):
        plan.light[name] = [a0.lerp(a1, ease_io(f / (n - 1))) for f in range(n)]
    return plan


# ---------------------------------------------------------------------------------------
# WALL2LIB: poster wall -> library
# ---------------------------------------------------------------------------------------
STAGE_R0 = Quaternion((1, 0, 0), math.pi / 2)    # stage-local (x right, y up, z to viewer) -> world (z up, wall at +y)
CARD_KINDS = ("card", "poster", "poster_card", "tile")


def is_card(e):
    return e.kind in CARD_KINDS or e.id.startswith(("card", "poster"))


def load_posterwall():
    import importlib.util
    spec = importlib.util.spec_from_file_location("posterwall", os.path.join(os.path.dirname(os.path.abspath(__file__)), "posterwall.py"))
    m = importlib.util.module_from_spec(spec)
    sys.modules["posterwall"] = m
    spec.loader.exec_module(m)
    return m


def make_card_material(name, hires, crop_png, e=None):
    """Poster card: lit paper look on the wall (emit=0) -> exact emission UI (emit=1); hi-res art crossfades
    (xf 0 -> 1) to the exact key-frame crop (colour and rounded alpha)."""
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")

    def tex(path, interp, alpha=False):
        n = nt.nodes.new("ShaderNodeTexImage")
        img = bpy.data.images.load(path, check_existing=True)
        img.colorspace_settings.name = "sRGB"
        if alpha:
            img.alpha_mode = "STRAIGHT"
        n.image, n.interpolation, n.extension = img, interp, "EXTEND"
        return n

    def attr(key):
        n = nt.nodes.new("ShaderNodeAttribute")
        n.attribute_type, n.attribute_name = "OBJECT", f'["{key}"]'
        return n

    def math_(op, a=None, b=None, clamp=False):
        n = nt.nodes.new("ShaderNodeMath")
        n.operation, n.use_clamp = op, clamp
        for i, v in enumerate((a, b)):
            if v is None:
                continue
            if isinstance(v, (int, float)):
                n.inputs[i].default_value = v
            else:
                nt.links.new(v, n.inputs[i])
        return n.outputs[0]

    hi, cr = tex(hires, "Cubic"), tex(crop_png, "Linear", True)
    if e is not None:
        # the hi-res poster covers the nominal box of the card, not the (padded) crop rectangle
        bx0, by0, bx1, by1 = e.box
        bw, bh = bx1 - bx0, by1 - by0
        uv = nt.nodes.new("ShaderNodeTexCoord")
        mp = nt.nodes.new("ShaderNodeMapping")
        mp.vector_type = "POINT"
        mp.inputs["Scale"].default_value = (e.w / bw, e.h / bh, 1.0)
        mp.inputs["Location"].default_value = ((e.x - bx0) / bw, 1.0 - (e.y - by0) / bh - e.h / bh, 0.0)
        nt.links.new(uv.outputs["UV"], mp.inputs["Vector"])
        nt.links.new(mp.outputs["Vector"], hi.inputs["Vector"])
    fade, emit, xf = attr("fade").outputs["Fac"], attr("emit").outputs["Fac"], attr("xf").outputs["Fac"]
    mx = nt.nodes.new("ShaderNodeMix")
    mx.data_type = "RGBA"
    nt.links.new(xf, mx.inputs[0])
    nt.links.new(hi.outputs["Color"], mx.inputs[6])
    nt.links.new(cr.outputs["Color"], mx.inputs[7])
    lit = nt.nodes.new("ShaderNodeBsdfPrincipled")
    nt.links.new(hi.outputs["Color"], lit.inputs["Base Color"])
    nt.links.new(hi.outputs["Color"], lit.inputs["Emission Color"])
    lit.inputs["Emission Strength"].default_value = 0.02
    lit.inputs["Roughness"].default_value = 0.45
    lit.inputs["Specular IOR Level"].default_value = 0.35
    lit.inputs["Coat Weight"].default_value = 0.18
    lit.inputs["Coat Roughness"].default_value = 0.22
    em = nt.nodes.new("ShaderNodeEmission")
    nt.links.new(mx.outputs[2], em.inputs[0])
    nt.links.new(fade, em.inputs[1])
    sh = nt.nodes.new("ShaderNodeMixShader")
    nt.links.new(emit, sh.inputs[0])
    nt.links.new(lit.outputs[0], sh.inputs[1])
    nt.links.new(em.outputs[0], sh.inputs[2])
    # alpha: 1 on the wall, the crop's rounded alpha once xf = 1
    a1 = math_("SUBTRACT", 1.0, cr.outputs["Alpha"])
    a2 = math_("MULTIPLY", xf, a1)
    a3 = math_("SUBTRACT", 1.0, a2)
    a4 = math_("MULTIPLY", a3, math_("MULTIPLY", fade, 6.0, True), True)
    tr = nt.nodes.new("ShaderNodeBsdfTransparent")
    fin = nt.nodes.new("ShaderNodeMixShader")
    nt.links.new(a4, fin.inputs[0])
    nt.links.new(tr.outputs[0], fin.inputs[1])
    nt.links.new(sh.outputs[0], fin.inputs[2])
    nt.links.new(fin.outputs[0], out.inputs[0])
    return m


def block_mean(a, th, tw):
    ys = np.linspace(0, a.shape[0], th + 1).astype(int)
    xs = np.linspace(0, a.shape[1], tw + 1).astype(int)
    out = np.zeros((th, tw, a.shape[2]), np.float32)
    for i in range(th):
        for j in range(tw):
            out[i, j] = a[ys[i]:max(ys[i + 1], ys[i] + 1), xs[j]:max(xs[j + 1], xs[j] + 1)].mean((0, 1))
    return out


def poster_thumb(path):
    img = bpy.data.images.load(str(path))
    img.colorspace_settings.name = "Non-Color"
    img.scale(40, 60)
    a = np.empty(40 * 60 * 4, np.float32)
    img.pixels.foreach_get(a)
    bpy.data.images.remove(img)
    return a.reshape(60, 40, 4)[::-1, :, :3].copy()


def crop_thumb(e):
    """40 px wide thumbnail of the visible part of a card (rows of the 60 px tall poster thumb it covers)."""
    px = load_pixels(e.png, raw=True)
    bx0, by0, bx1, by1 = e.box
    vis_bottom = min(by1, FH, e.y + px.shape[0])
    sub = px[int(round(by0 - e.y)):int(round(vis_bottom - e.y)), int(round(bx0 - e.x)):int(round(bx1 - e.x)), :3]
    th = max(4, int(round(60 * (vis_bottom - by0) / (by1 - by0))))
    return block_mean(sub, th, 40)


def stage_to_world(O, ls, loc, q, sc):
    return O + STAGE_R0 @ (loc * ls), STAGE_R0 @ q, sc * ls


def plan_wall2lib(a, P, lay, seed):
    pw = load_posterwall()
    from pathlib import Path
    beat = 60.0 / P["bpm"]
    n = int(round(P["w2l_duration_s"] * FPS)) + 1
    T = (n - 1) / FPS
    plan = Plan(n)
    plan.own_lights = True
    plan.world_up = (0, 0, 1)
    plan.fstop = P["w2l_fstop"]
    plan.key = lay["key"]
    sc = bpy.context.scene
    R = pw.RADIUS
    posters_dir = Path(REPO) / "build" / "promo" / "cache" / "posters"
    posters = pw.load_posters(posters_dir)
    layout = pw.Layout(posters)
    pw.setup_world()
    pw.build_wall(layout, posters_dir)
    wall_objs = [o for o in bpy.context.scene.objects if o.type == "MESH"]
    root = bpy.data.objects.new("wallroot", None)
    sc.collection.objects.link(root)
    for o in wall_objs:
        o.parent = root
    # ---- library cards -> wall slots ---------------------------------------------------------
    for e in lay["els"]:
        analyse(e)
    assign_rest_z(lay["els"])
    cards = [e for e in lay["els"] if is_card(e)]
    cards.sort(key=lambda e: e.cy)
    rows, cur = [], []
    for e in cards:
        if cur and e.cy - cur[-1].cy > 0.4 * e.h:
            rows.append(cur)
            cur = []
        cur.append(e)
    rows.append(cur)
    slot = {}
    r0 = 1 if len(rows) <= 4 else 0
    for ri, row in enumerate(rows):
        for ci, e in enumerate(sorted(row, key=lambda e: e.cx)):
            slot[e.id] = (P["w2l_block_col"] + ci, min(r0 + ri, pw.ROWS - 1))
    widths = sorted(e.box[2] - e.box[0] for e in cards)
    ls = pw.POSTER[0] / (widths[len(widths) // 2] * PX)
    # poster art by title, unmatched cards take unused posters
    extra_file = posters_dir / "collections.json"
    extras = json.loads(extra_file.read_text(encoding="utf-8")) if extra_file.is_file() else []
    by_title = {p["title"].lower(): p for p in posters + extras}
    used, art = set(), {}
    for e in cards:
        t = e.title.lower().split(" (")[0]
        if t in by_title:
            art[e.id] = by_title[t]["slug"]
            used.add(by_title[t]["slug"])
    # the library grid is sorted by name: only the first ~24 titles can be on its first screen
    first_screen = sorted((p for p in posters + extras), key=lambda p: p["title"].lower())[:24]
    spare = [p["slug"] for p in first_screen if p["slug"] not in used]
    if any(e.id not in art for e in cards):
        thumbs = {sl: poster_thumb(posters_dir / f"{sl}.jpg") for sl in spare}
    for e in cards:
        if e.id not in art:
            ct = crop_thumb(e)
            best = min(spare, key=lambda sl: float(np.mean((thumbs[sl][:ct.shape[0]] - ct) ** 2)))
            spare.remove(best)
            art[e.id] = best
            print(f"[wall2lib] no poster titled {e.title!r} for {e.id}: matched {best} by image similarity")
    hires = {k: str(posters_dir / f"{v}.jpg") for k, v in art.items()}
    # stage origin from the centroid of cards and slots
    wp = {e.id: pw.wall_point(*layout.position(*slot[e.id]), R - pw.FRONT) for e in cards}
    mx_ = sum(p.x for p in wp.values()) / len(wp)
    mh_ = sum(p.z for p in wp.values()) / len(wp)
    cx_ = sum((e.cx - FW / 2) * PX * ls for e in cards) / len(cards)
    cy_ = sum((FH / 2 - e.cy) * PX * ls for e in cards) / len(cards)
    O = Vector((mx_ - cx_, P["w2l_stage_y"], mh_ - cy_))
    # hide the wall posters that the cards replace
    for o in wall_objs:
        if o.name == "wall":
            continue
        pos = o.matrix_world.translation
        if any((pos - p).length < 0.2 for p in wp.values()):
            o.hide_render = True
    # ---- slabs ----------------------------------------------------------------------------------
    y_plate = R + 0.03
    base, slabs = build_slabs(lay, base_zf=-(y_plate - O.y) / ls, hires=hires, base_glossy=0.0, glossy=0.06)
    plan.slabs = [base] + slabs
    c1 = P["overshoot"]
    wave0 = P["w2l_detach_beat"]
    for s in slabs:
        e = s.el
        r = rng_for(seed, e.id)
        F_w, q_f, sc_f = stage_to_world(O, ls, s.pos, Quaternion((1, 0, 0, 0)), s.k)
        if is_card(e):
            col, row = slot[e.id]
            sp, hp = layout.position(col, row)
            P0 = pw.wall_point(sp, hp, R - pw.FRONT)
            q0 = pw.basis(sp / R, 0.0).to_quaternion()
            rel = STAGE_R0.inverted() @ q0
            axis, ang = rel.to_axis_angle()
            if ang > math.pi:
                ang -= 2 * math.pi
            ci, ri = col - P["w2l_block_col"], row - r0
            t0 = (wave0 + ci * P["w2l_col_stagger_beats"] + ri * P["w2l_row_stagger_beats"]) * beat + r.uniform(-0.04, 0.04)
            D = P["w2l_flight_beats"] * beat * r.uniform(0.94, 1.06)
            C1 = P0 + (F_w - P0) * 0.25 + Vector((0, -r.uniform(1.2, 2.2), 0)) + Vector((r.choice((-1, 1)) * r.uniform(0.4, 0.9), 0, r.uniform(-0.4, 0.5)))
            C2 = F_w + Vector((r.uniform(-0.25, 0.25), -r.uniform(0.6, 1.1), r.uniform(-0.2, 0.2)))
            a1, a2 = math.radians(r.uniform(18, 42)) * r.choice((-1, 1)), math.radians(r.uniform(10, 30)) * r.choice((-1, 1))
            sc0 = pw.POSTER[0] / ((e.box[2] - e.box[0]) * PX)
            emit_l, xf_l = [], []
            for f in range(n):
                t = f / FPS
                sn = (t - t0) / D
                u = clamp01(sn)
                eu = ease_out_back(sn, c1 * 0.8)
                loc = P0 if sn < 0 else bezier(P0, C1, C2, F_w, eu)
                q = STAGE_R0 @ Quaternion(axis, ang * (1 - eu))
                q = qaxis((0, 0, 1), a1 * math.sin(math.pi * u)) @ qaxis((1, 0, 0), a2 * math.sin(math.pi * u)) @ q
                scale = lerp(sc0, sc_f, 1 - (1 - u) ** 3)
                s.poses.append((loc, q, scale, 1.0))
                emit_l.append(smoothstep(0.3, 0.95, sn))
                xf_l.append(smoothstep(T - P["w2l_xf_s"], T - 0.1, t))
            s.props = dict(emit=emit_l, xf=xf_l)
        else:
            cb = P["w2l_chrome_beat"]
            if e.kind == "nav":
                st = cb + 0.05 * (e.cx / 400.0)
            elif e.kind == "chip":
                st = cb + 0.5 + 0.05 * (e.cx / 300.0)
            elif e.kind in ("rail", "avatar"):
                st = cb + 0.7 + (0.0 if not e.parent else 0.8 * (e.cy - 365) / 590.0)
            else:
                st = cb + 1.1 + 0.5 * (e.cx / FW) + 0.15 * (e.cy / FH)
            t0 = st * beat
            D = 0.9 * beat
            off = Vector((0.0, 0.7, 0.45))     # slide down from slightly above, hover a few px in front, no tumble
            for f in range(n):
                sn = (f / FPS - t0) / D
                u = ease_out_back(sn, 0.0)
                fade = smoothstep(0.0, 0.7, sn) if sn >= 0 else 0.0
                lw, qw, sw_ = stage_to_world(O, ls, s.pos + off * (1.0 - u), Quaternion((1, 0, 0, 0)), s.k)
                s.poses.append((lw, qw, sw_, fade))
            s.props = dict(xf=[1.0] * n)
    # base plate: constant place, brightness/alpha ramp
    Fb, qb, sb_ = stage_to_world(O, ls, base.pos, Quaternion((1, 0, 0, 0)), base.k)
    pt0, pt1 = P["w2l_plate_beat"] * beat, (P["w2l_plate_beat"] + P["w2l_plate_beats"]) * beat
    for f in range(n):
        t = f / FPS
        base.poses.append((Fb, qb, sb_, smoothstep(pt0, pt1, t) ** 1.2))
    base.props = dict(xf=[1.0] * n)
    # ---- camera ---------------------------------------------------------------------------------------
    sb = layout.position(P["w2l_block_col"] + 3, 2)[0]
    hl = mh_
    G = P["w2l_glide_s"]
    finalpos = Vector((O.x, O.y - CAM_D * ls, O.z))
    finallook = Vector((O.x, O.y, O.z))
    K0 = (pw.wall_point(sb - 4.8, hl + 0.15, R - 5.4), pw.wall_point(sb - 3.4, hl, R), 35.0)
    K1 = (Vector((O.x - 0.9, 0.9, O.z + 0.15)), Vector((O.x, O.y + 1.2, O.z)), 38.0)
    K2 = (finalpos + Vector((0.0, -0.35, 0.05)), finallook, 39.0)
    mids = []
    if P["w2l_duration_s"] > 12.0:      # long glide: a second pass at a different height, gentle push-in
        mids = [(0.35 * G, (pw.wall_point(sb - 3.0, hl + 0.55, R - 5.0), pw.wall_point(sb - 1.9, hl + 0.25, R), 36.0)),
                (0.72 * G, (pw.wall_point(sb - 1.2, hl - 0.55, R - 4.3), pw.wall_point(sb - 0.2, hl - 0.2, R), 37.0))]
    keys = {c: [] for c in ("px", "py", "pz", "lx", "ly", "lz", "ln")}
    for t_, (pp, ll, ln) in [(0.0, K0)] + mids + [(G, K1), (G + 0.52 * (T - G), K2), (T, (finalpos, finallook, LENS))]:
        for c, v in zip(keys, (pp.x, pp.y, pp.z, ll.x, ll.y, ll.z, ln)):
            keys[c].append((t_, v))
    prev = None
    plan.lens = []
    for f in range(n):
        t = f / FPS
        v = {c: pw.spline(kv, t) for c, kv in keys.items()}
        pos, look = Vector((v["px"], v["py"], v["pz"])), Vector((v["lx"], v["ly"], v["lz"]))
        q = look_quat(pos, look, (0, 0, 1))
        if prev is not None and q.dot(prev) < 0:
            q = Quaternion((-q.w, -q.x, -q.y, -q.z))
        prev = q
        plan.cam.append((pos, q, (look - pos).length))
        plan.lens.append(v["ln"])
    plan.fx = fx_curve(n, P["fx_frames"])
    # ---- lights, haze, wall recede ------------------------------------------------------------------------
    key = pw.make_light("key", "SPOT", pw.BEAM, 0.35, 1.0)
    wash = pw.make_light("wash", "SPOT", (0.8, 0.95, 1.0), 0.6, 1.0)
    wash.data.spot_size = math.radians(75)
    vol = bpy.context.scene.world.node_tree.nodes["Volume Scatter"].inputs["Density"]
    off0, off1 = P["w2l_lights_off_beat"] * beat, (P["w2l_lights_off_beat"] + P["w2l_lights_off_beats"]) * beat
    rc0, rc1 = P["w2l_recede_beat"] * beat, (P["w2l_recede_beat"] + P["w2l_recede_beats"]) * beat

    def dimming(t):
        return 1.0 - smoother((t - off0) / (off1 - off0))

    def keyer(cam, lights_):
        for f in range(n):
            t = f / FPS
            dim = dimming(t)
            ks = lerp(sb - 5.0, sb - 0.3, ease_io(t / G))
            ki = smoothstep(0.0, 0.5, t)
            wi = lerp(0.0, 0.12, smoothstep(0.0, 1.2, t))
            aim_ = pw.wall_point(ks, hl)
            th = (ks - 1.2) / R
            kp = Vector(((R - 7.5) * math.sin(th), (R - 7.5) * math.cos(th), hl + 3.2))
            key.location, key.rotation_quaternion = kp, look_quat(kp, aim_, (0, 0, 1))
            key.data.energy = 5200.0 * ki * dim
            key.data.spot_size = math.radians(44 * 0.58)
            cp = plan.cam[f][0]
            wp_ = cp + Vector((0, 0, 0.6))
            dist = min(max(2.0, (aim_ - wp_).length), 5.5)
            wash.location, wash.rotation_quaternion = wp_, look_quat(wp_, aim_, (0, 0, 1))
            wash.data.energy = 13.0 * dist ** 2 * wi * dim
            for ob in (key, wash):
                ob.keyframe_insert("location", frame=f + 1)
                ob.keyframe_insert("rotation_quaternion", frame=f + 1)
                ob.data.keyframe_insert("energy", frame=f + 1)
            root.location = (0, P["w2l_recede_m"] * ease_io((t - rc0) / (rc1 - rc0)), 0)
            root.keyframe_insert("location", frame=f + 1)

    plan.keyer = keyer
    plan.per_frame = lambda f: setattr(vol, "default_value", 0.02 * dimming(f / FPS) * (1.0 - 0.85 * smoothstep(G - 0.5, G + 1.5, f / FPS)))
    plan.dust_spec = dict(n=int(P["w2l_dust"]), zr=(4.0, 7.4), box=((-5.5, 5.5), (hl - 1.8, hl + 2.2)), size=(0.003, 0.008),
                          strength=1.6, vel=0.05, axes="xzy")
    plan.O, plan.ls = O, ls
    return plan


# ---------------------------------------------------------------------------------------
# LIB2LOGO: library -> poster-diamond Glacier logo (bookend of the opening)
# ---------------------------------------------------------------------------------------
ACCENT_LIN = (0.205, 0.571, 0.694)      # #7DC7D9 in linear light


class Stub:
    """Minimal slab-like holder (object + per frame poses) for things that are not layout elements."""

    def __init__(self, ob):
        self.ob = ob
        self.poses = []
        self.props = {}
        self.el = None


def make_line_material(name, strength, glow):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    tc = nt.nodes.new("ShaderNodeTexCoord")
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(tc.outputs["UV"], sep.inputs[0])

    def attr(key):
        n = nt.nodes.new("ShaderNodeAttribute")
        n.attribute_type, n.attribute_name = "OBJECT", f'["{key}"]'
        return n.outputs["Fac"]

    def mth(op, a=None, b=None, clamp=False):
        n = nt.nodes.new("ShaderNodeMath")
        n.operation, n.use_clamp = op, clamp
        for i, v in enumerate((a, b)):
            if v is None:
                continue
            if isinstance(v, (int, float)):
                n.inputs[i].default_value = v
            else:
                nt.links.new(v, n.inputs[i])
        return n.outputs[0]

    trace, fade = attr("trace"), attr("fade")
    x, y = sep.outputs[0], sep.outputs[1]
    reveal = mth("LESS_THAN", x, trace)
    cross = mth("SUBTRACT", 1.0, mth("ABSOLUTE", mth("SUBTRACT", mth("MULTIPLY", y, 2.0), 1.0)), clamp=True)
    hot = mth("POWER", mth("SUBTRACT", 1.0, mth("MULTIPLY", mth("SUBTRACT", trace, x), 6.0), clamp=True), 2.0)
    if glow:
        alpha = mth("MULTIPLY", mth("POWER", cross, 2.0), reveal)
        alpha = mth("MULTIPLY", alpha, 0.4)
    else:
        alpha = reveal
    alpha = mth("MULTIPLY", alpha, mth("MULTIPLY", fade, 6.0, True), True)
    em = nt.nodes.new("ShaderNodeEmission")
    em.inputs[0].default_value = (*ACCENT_LIN, 1)
    nt.links.new(mth("MULTIPLY", mth("ADD", 1.0, mth("MULTIPLY", hot, 3.0)), mth("MULTIPLY", fade, strength)), em.inputs[1])
    tr = nt.nodes.new("ShaderNodeBsdfTransparent")
    mx = nt.nodes.new("ShaderNodeMixShader")
    nt.links.new(alpha, mx.inputs[0])
    nt.links.new(tr.outputs[0], mx.inputs[1])
    nt.links.new(em.outputs[0], mx.inputs[2])
    nt.links.new(mx.outputs[0], out.inputs[0])
    return m


def make_line_object(name, length, width, mat):
    me = bpy.data.meshes.new(name)
    hl, hw = length / 2.0, width / 2.0
    me.from_pydata([(-hl, -hw, 0), (hl, -hw, 0), (hl, hw, 0), (-hl, hw, 0)], [], [(0, 1, 2, 3)])
    uv = me.uv_layers.new(name="UV")
    for li, (u, v) in zip(me.polygons[0].loop_indices, ((0, 0), (1, 0), (1, 1), (0, 1))):
        uv.data[li].uv = (u, v)
    me.materials.append(mat)
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    ob.rotation_mode = "QUATERNION"
    ob["fade"] = 1.0
    ob["emit"] = 1.0
    ob["xf"] = 1.0
    ob["trace"] = 0.0
    ob.visible_shadow = False
    return ob


def small_poster(slug, posters_dir):
    """216x324 copy of a poster (Cycles has no mipmaps: a 1000x1500 image downscaled to ~80 px would sparkle)."""
    out_dir = os.path.join(REPO, "build", "promo", "cache", "posters-small")
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, slug + ".png")
    if not os.path.isfile(out):
        img = bpy.data.images.load(str(posters_dir / f"{slug}.jpg"))
        img.scale(216, 324)
        img.filepath_raw = out
        img.file_format = "PNG"
        img.save()
        bpy.data.images.remove(img)
    return out


def pinwheel(n, a, g, grp):
    """Pinwheel of 4 strips of n cards (short side a, long side 1.5 a) around the origin in (u, v) = diamond axes.
    Returns slots [(x, y, angle, grp)] and the side length S of the square it fills."""
    L = 1.5 * a
    Lp = L + g
    S = a + n * Lp
    c = math.sqrt(0.5)
    eu, ev = (c, c), (-c, c)
    slots = []

    def add(u, v, long_along_u):
        slots.append((u * eu[0] + v * ev[0], u * eu[1] + v * ev[1], math.radians(-45 if long_along_u else 45), grp))
    for i in range(n):
        add(-S / 2 + L / 2 + i * Lp, -S / 2 + a / 2, True)            # bottom strip, long side along u
        add(S / 2 - a / 2, -S / 2 + L / 2 + i * Lp, False)            # right strip, long side along v
        add(S / 2 - L / 2 - i * Lp, S / 2 - a / 2, True)              # top strip
        add(-S / 2 + a / 2, S / 2 - L / 2 - i * Lp, False)            # left strip
    return slots, S


def logo_slots(P):
    """Glacier mark from posters: outer pinwheel ring (4 x ring_n cards) + inner pinwheel (4 cards), all 2:3 cards
    tilted 45 degrees. Returns slots, half-diagonal H of the outer diamond and its side S."""
    a, g = P["l2l_card"], P["l2l_gap"]
    ring, S = pinwheel(int(P["l2l_ring_n"]), a, g, "ring")
    inner, _ = pinwheel(int(P["l2l_inner_n"]), a, g, "inner")
    return ring + inner, S / math.sqrt(2.0), S


def plan_lib2logo(a, P, lay, seed):
    from pathlib import Path
    beat = 60.0 / P["bpm"]
    n = int(round(P["l2l_duration_s"] * FPS)) + 1
    T = (n - 1) / FPS
    plan = Plan(n)
    plan.key = lay["key"]
    plan.fstop = P["l2l_fstop"]
    posters_dir = Path(REPO) / "build" / "promo" / "cache" / "posters"
    base, slabs = build_slabs(lay, hires={}, base_glossy=0.0, glossy=0.10, sweep=True)
    plan.slabs = [base] + slabs
    cards = [s for s in slabs if is_card(s.el)]
    chrome = [s for s in slabs if not is_card(s.el)]
    slots, H, S = logo_slots(P)
    C = Vector((0.0, P["l2l_center_y"], 0.0))
    # ---- extra posters from the cache (+ collections), from the darkness ---------------------------
    index = json.loads((posters_dir / "index.json").read_text(encoding="utf-8"))
    extra_file = posters_dir / "collections.json"
    index += json.loads(extra_file.read_text(encoding="utf-8")) if extra_file.is_file() else []
    used_titles = {s.el.title.lower() for s in cards if s.el.title}
    pool = [p for p in index if p["title"].lower() not in used_titles]
    rng = random.Random(seed + 5)
    rng.shuffle(pool)
    n_extra = len(slots) - len(cards)
    extras = []
    for i in range(n_extra):
        p = pool[i]
        d = dict(id=f"extra_{i}", kind="poster", png=small_poster(p["slug"], posters_dir), x=0, y=0, w=216, h=324,
                 z_order=500 + i, group="extra", title=p["title"], corner_radius=22, box=[0, 0, 216, 324])
        e = El(d, "")
        e.png = d["png"]
        e.png_use = e.png
        analyse(e)
        e.solid = True
        e.radii = (22.0,) * 4
        fm = make_front_material(e.id + "_front", e.png, False, glossy=0.10, sweep=True)
        edge = make_edge_material(e.id + "_edge", e.tint)
        back = make_back_material(e.id + "_back", e.tint)
        ob = make_slab(e.id, e, 0.06, fm, edge, back)
        s = Slab(e, ob)
        s.is_base = False
        extras.append(s)
    plan.slabs += extras
    # ---- slot assignment: LIB posters take the nearest free slots ------------------------------------
    free = list(range(len(slots)))
    assign = {}
    for s in sorted(cards, key=lambda s: (s.pos.x, s.pos.y)):
        j = min(free, key=lambda k: (slots[k][0] + C.x - s.pos.x) ** 2 + (slots[k][1] + C.y - s.pos.y) ** 2)
        free.remove(j)
        assign[s.el.id] = j
    for s, j in zip(extras, free):
        assign[s.el.id] = j
    # ---- logo group motion (breathing) ----------------------------------------------------------------
    t_form = P["l2l_trace_beat"] * beat

    def group(t):
        g = smoothstep(t_form - 1.5, t_form + 0.5, t)
        ry = math.radians(3.2) * math.sin(2 * math.pi * (t - t_form) / 6.0) * g
        rx = math.radians(1.6) * math.sin(2 * math.pi * (t - t_form) / 7.3 + 1.0) * g
        br = 1.0 + 0.012 * math.sin(2 * math.pi * t / 4.5) * g
        bob = 0.06 * math.sin(2 * math.pi * t / 5.2) * g
        return (qaxis((0, 1, 0), ry) @ qaxis((1, 0, 0), rx)), br, Vector((0, bob, 0))

    def target(slot, t):
        x, y, ang, _ = slot
        q, br, bob = group(t)
        return C + bob + q @ Vector((x * br, y * br, 0.0)), q @ qaxis((0, 0, 1), ang), br

    c1 = P["overshoot"]
    tgt_w = P["l2l_card"]
    # ---- chrome: reverse of the arrival (lift slightly, fade out) ------------------------------------------
    cb = P["l2l_chrome_beat"]
    for s in chrome:
        e = s.el
        if e.kind == "nav":
            st = cb + 1.2 - 0.05 * (e.cx / 400.0)
        elif e.kind == "chip":
            st = cb + 0.9 - 0.05 * (e.cx / 300.0)
        elif e.kind in ("rail", "avatar"):
            st = cb + 0.5 + (0.0 if not e.parent else 0.8 * (1.0 - (e.cy - 365) / 590.0))
        else:
            st = cb + 0.0 + 0.5 * (1.0 - e.cx / FW)
        t0, D = st * beat, 0.9 * beat
        off = Vector((0.0, 0.7, 0.45))
        for f in range(n):
            sn = (f / FPS - t0) / D
            u = ease_io(sn)
            fade = 1.0 - smoothstep(0.0, 0.85, sn)
            s.poses.append((s.pos + off * u, Quaternion((1, 0, 0, 0)), s.k, fade))
    # ---- base plate dims ------------------------------------------------------------------------------------
    pt0, pt1 = P["l2l_plate_beat"] * beat, (P["l2l_plate_beat"] + P["l2l_plate_beats"]) * beat
    for f in range(n):
        base.poses.append((base.pos, Quaternion((1, 0, 0, 0)), base.k, 1.0 - smoothstep(pt0, pt1, f / FPS)))
    # ---- posters ---------------------------------------------------------------------------------------------
    for s in cards + extras:
        e = s.el
        r = rng_for(seed, e.id)
        slot = slots[assign[e.id]]
        bw = (e.box[2] - e.box[0]) * PX
        sc_t = tgt_w / bw
        if is_card(e) and not e.id.startswith("extra"):
            row = int(e.group.replace("row", "")) if e.group.startswith("row") else 0
            col = int(e.id.split("c")[-1]) if "c" in e.id else 0
            t0 = (P["l2l_detach_beat"] + col * P["l2l_col_stagger_beats"] + row * P["l2l_row_stagger_beats"]) * beat
            D = P["l2l_flight_beats"] * beat * r.uniform(0.94, 1.06)
            P0, q0, s0, fade_in = s.pos, Quaternion((1, 0, 0, 0)), s.k, 0.0
            far = False
        else:
            k = int(e.id.split("_")[1])
            t0 = (P["l2l_extra_beat"] + k * P["l2l_extra_stagger_beats"]) * beat
            D = P["l2l_extra_flight_beats"] * beat * r.uniform(0.94, 1.06)
            ang = r.uniform(0, 2 * math.pi)
            P0 = Vector((math.cos(ang) * r.uniform(9, 17), math.sin(ang) * r.uniform(5, 9), r.uniform(-18, -7)))
            q0 = qaxis((0, 1, 0), math.radians(r.uniform(-25, 25))) @ qaxis((0, 0, 1), math.radians(r.uniform(-90, 90)))
            s0, fade_in = sc_t * 0.7, 0.35
            far = True
        out = Vector((r.uniform(-1, 1), r.uniform(-0.6, 0.6), 0)).normalized() * r.uniform(5, 10)
        axes = [(1, 0, 0), (0, 1, 0), (0, 0, 1)]
        # the face must never come near edge-on: tilt about x / y stays within ~35 degrees, the spin about z is free
        amp = [math.radians(r.uniform(18, 33)) * r.choice((-1, 1)), math.radians(r.uniform(18, 33)) * r.choice((-1, 1)),
               math.radians(r.uniform(70, 170)) * r.choice((-1, 1))]
        C1 = P0 + out + Vector((0, 0, r.uniform(-5, 2.5)))
        for f in range(n):
            t = f / FPS
            sn = (t - t0) / D
            u = clamp01(sn)
            Ft, qt, br = target(slot, t)
            C2 = Ft + Vector((r.uniform(-1, 1) * 0.0 + math.sin(7.0 * len(e.id)) * 2.5, math.cos(5.0 * len(e.id)) * 2.0, 2.5))
            ep = ease_out_back(sn, c1)
            loc = P0 if sn < 0 else bezier(P0, C1, C2, Ft, ep)
            qb = q0.slerp(qt, ease_io(u))
            q = qb
            for ax, am in zip(axes, amp):
                q = qaxis(ax, am * math.sin(math.pi * u)) @ q
            scale = lerp(s0, sc_t * br, ease_io(u)) * (1.0 + 0.12 * math.sin(math.pi * u))
            fade = (smoothstep(0.0, fade_in, sn) if sn >= 0 else 0.0) if far else 1.0
            s.poses.append((loc, q, scale, fade))
        s.props = dict(xf=[1.0] * n, emit=[1.0] * n)
    for s in plan.slabs:
        if not hasattr(s, "props") or not s.props:
            s.props = dict(xf=[1.0] * n)
    # ---- cyan diamond outline that traces around the logo on the final hit ---------------------------------------
    Ho = H + P["l2l_outline_margin"]
    tips = {"T": Vector((0, Ho, 0)), "R": Vector((Ho, 0, 0)), "B": Vector((0, -Ho, 0)), "L": Vector((-Ho, 0, 0))}
    edges = [("T", "R", 0, 0.0), ("R", "B", 0, 1.0), ("T", "L", 1, 0.0), ("L", "B", 1, 1.0)]
    mats = {False: make_line_material("line_core", 3.0, False), True: make_line_material("line_glow", 1.2, True)}
    tt0, tt1 = P["l2l_trace_beat"] * beat, (P["l2l_trace_beat"] + P["l2l_trace_beats"]) * beat
    hit = tt1
    for glow in (False, True):
        for (a_, b_, chain, half) in edges:
            A, B = tips[a_], tips[b_]
            Lg = (B - A).length
            ob = make_line_object(f"line_{a_}{b_}_{'g' if glow else 'c'}", Lg, 0.055 if not glow else 0.32, mats[glow])
            st = Stub(ob)
            ang = math.atan2((B - A).y, (B - A).x)
            mid = (A + B) / 2
            tr_l, fa_l = [], []
            for f in range(n):
                t = f / FPS
                p = smoother((t - tt0) / (tt1 - tt0))
                trv = clamp01(p * 2.0 - half)
                q, br, bob = group(t)
                loc = C + bob + q @ (mid * br + Vector((0, 0, 0.3)))
                st.poses.append((loc, q @ qaxis((0, 0, 1), ang), br, 1.0 + 1.6 * math.exp(-max(0.0, t - hit) * 4.0) * (1.0 if t >= hit else 0.0)))
                tr_l.append(trv)
            st.props = dict(trace=tr_l, xf=[1.0] * n, emit=[1.0] * n)
            plan.slabs.append(st)
    # ---- camera: flat start, slow pull-back and orbit ---------------------------------------------------------------
    prev = None
    for f in range(n):
        t = f / FPS
        e_ = smoother(t / T)
        pullf = smoother(t / t_form)                       # slow pull-back while the posters fly ...
        pushf = smoother((t - t_form + 0.5) / (T - t_form + 0.5))   # ... then a gentle push-in during the hold
        th = math.radians(-6.0) * e_ + math.radians(1.6) * math.sin(2 * math.pi * t / 7.0) * e_
        el_ = math.radians(2.0) * e_
        dist = CAM_D * (1.0 + P["l2l_pull"] * pullf) * (1.0 - P["l2l_push"] * pushf)
        pos = Vector((math.sin(th) * math.cos(el_) * dist, math.sin(el_) * dist, math.cos(th) * math.cos(el_) * dist))
        q = look_quat(pos, (0, 0, 0))
        if prev is not None and q.dot(prev) < 0:
            q = Quaternion((-q.w, -q.x, -q.y, -q.z))
        prev = q
        plan.cam.append((pos, q, pos.length))
    # effects ramp in from zero (frame 1 is the flat key frame)
    plan.fx = [smoother((f - 2) / float(P["fx_frames"])) for f in range(n)]
    # light sweep across the formed logo
    for name, a0, a1 in (("key", Vector((-16, 6, 9)), Vector((16, 9, 10))),
                         ("rim", Vector((14, -6, 6)), Vector((-8, -4, 9))),
                         ("top", Vector((-2, 16, 6)), Vector((3, 15, 9)))):
        plan.light[name] = [a0.lerp(a1, smoother(max(0.0, f / FPS - 3.5) / (T - 3.5))) for f in range(n)]
    # ---- soft cyan bloom behind the outline (diamond shaped), swells on the hit ------------------------------------
    bm = bpy.data.materials.new("bloom")
    bm.use_nodes = True
    nt = bm.node_tree
    nt.nodes.clear()
    out_ = nt.nodes.new("ShaderNodeOutputMaterial")
    tc = nt.nodes.new("ShaderNodeTexCoord")
    sp = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(tc.outputs["UV"], sp.inputs[0])

    def mth(op, a_=None, b_=None, clamp=False):
        nd = nt.nodes.new("ShaderNodeMath")
        nd.operation, nd.use_clamp = op, clamp
        for i_, v_ in enumerate((a_, b_)):
            if v_ is None:
                continue
            if isinstance(v_, (int, float)):
                nd.inputs[i_].default_value = v_
            else:
                nt.links.new(v_, nd.inputs[i_])
        return nd.outputs[0]
    l1 = mth("ADD", mth("ABSOLUTE", mth("SUBTRACT", sp.outputs[0], 0.5)), mth("ABSOLUTE", mth("SUBTRACT", sp.outputs[1], 0.5)))
    fall = mth("POWER", mth("SUBTRACT", 1.0, mth("MULTIPLY", l1, 2.0), clamp=True), 2.2)
    fd = nt.nodes.new("ShaderNodeAttribute")
    fd.attribute_type, fd.attribute_name = "OBJECT", '["fade"]'
    em_ = nt.nodes.new("ShaderNodeEmission")
    em_.inputs[0].default_value = (*ACCENT_LIN, 1)
    nt.links.new(mth("MULTIPLY", fd.outputs["Fac"], 0.9), em_.inputs[1])
    tr_ = nt.nodes.new("ShaderNodeBsdfTransparent")
    mx_ = nt.nodes.new("ShaderNodeMixShader")
    nt.links.new(mth("MULTIPLY", fall, mth("MULTIPLY", fd.outputs["Fac"], 4.0, True), True), mx_.inputs[0])
    nt.links.new(tr_.outputs[0], mx_.inputs[1]); nt.links.new(em_.outputs[0], mx_.inputs[2])
    nt.links.new(mx_.outputs[0], out_.inputs[0])
    size = 2.0 * (H + P["l2l_outline_margin"]) * 1.7
    me = bpy.data.meshes.new("bloom")
    hs = size / 2
    me.from_pydata([(-hs, -hs, 0), (hs, -hs, 0), (hs, hs, 0), (-hs, hs, 0)], [], [(0, 1, 2, 3)])
    uvl = me.uv_layers.new(name="UV")
    for li, uvc in zip(me.polygons[0].loop_indices, ((0, 0), (1, 0), (1, 1), (0, 1))):
        uvl.data[li].uv = uvc
    me.materials.append(bm)
    bob = bpy.data.objects.new("bloom", me)
    bpy.context.scene.collection.objects.link(bob)
    bob.rotation_mode = "QUATERNION"
    bob["fade"] = 0.0; bob["emit"] = 1.0; bob["xf"] = 1.0
    bob.visible_shadow = False
    bst = Stub(bob)
    for f in range(n):
        t = f / FPS
        ramp = smoothstep(tt0, tt1, t) * 0.35
        pulse = math.exp(-max(0.0, t - hit) * 3.0) * (1.0 if t >= hit else 0.0)
        bst.poses.append((C + Vector((0, 0, -1.2)), Quaternion((1, 0, 0, 0)), 1.0, ramp + 0.30 * smoothstep(hit - 0.1, hit + 0.4, t) + 0.55 * pulse))
    bst.props = dict(xf=[1.0] * n, emit=[1.0] * n)
    plan.slabs.append(bst)
    # ---- light sweeps over the formed logo (after the hit, and once more near the end) ---------------------------------
    def sweep_pos(t):
        for (a0, a1) in ((hit - 0.2, hit + 2.3), (T - 2.4, T - 0.1)):
            if a0 <= t <= a1:
                return lerp(-5.0, 6.0, ease_io((t - a0) / (a1 - a0)))
        return -99.0
    plan.per_frame = lambda f: [setattr(nd.outputs[0], "default_value", sweep_pos(f / FPS)) for nd in SWEEP_NODES] and None
    plan.dust_spec = dict(n=int(P["l2l_dust"]), strength=1.4, size=(0.015, 0.04))
    return plan


# ---------------------------------------------------------------------------------------
# applying a plan: keyframes + per-frame render settings
# ---------------------------------------------------------------------------------------


def keyframe_plan(plan, cam, lights):
    n = plan.n
    for s in plan.slabs:
        ob = s.ob
        poses = s.poses
        if not poses:
            continue
        first = 0
        while first < n - 1 and same_pose(poses[first], poses[0]):
            first += 1
        last = n - 1
        while last > 0 and same_pose(poses[last], poses[-1]):
            last -= 1
        lo, hi = max(0, first - 1), min(n - 1, last + 1)
        ob.location, ob.rotation_quaternion = poses[lo][0], poses[lo][1]
        ob.scale = (poses[lo][2],) * 3
        ob.rotation_mode = "QUATERNION"
        prevq = None
        for f in range(lo, hi + 1):
            loc, q, sc, fade = poses[f]
            if prevq is not None and q.dot(prevq) < 0:
                q = Quaternion((-q.w, -q.x, -q.y, -q.z))
            prevq = q
            ob.location, ob.rotation_quaternion, ob.scale = loc, q, (sc, sc, sc)
            ob.keyframe_insert("location", frame=f + 1)
            ob.keyframe_insert("rotation_quaternion", frame=f + 1)
            ob.keyframe_insert("scale", frame=f + 1)
        if hi < n - 1:
            ob.location, ob.rotation_quaternion = poses[-1][0], poses[-1][1]
    for f in range(n):
        pos, q, _ = plan.cam[f]
        cam.location, cam.rotation_quaternion = pos, q
        cam.keyframe_insert("location", frame=f + 1)
        cam.keyframe_insert("rotation_quaternion", frame=f + 1)
    if plan.lens:
        for f in range(n):
            cam.data.lens = plan.lens[f]
            cam.data.keyframe_insert("lens", frame=f + 1)
    if plan.keyer:
        plan.keyer(cam, lights)
    for name, ob in ({} if plan.own_lights else lights).items():
        for f in range(0, n, 3):
            ob.location = plan.light[name][f]
            aim(ob, (0, 0, 0))
            ob.keyframe_insert("location", frame=f + 1)
            ob.keyframe_insert("rotation_euler", frame=f + 1)
    for (ob, vel) in plan.dust:
        p0 = ob.location.copy()
        ob.keyframe_insert("location", frame=1)
        ob.location = p0 + vel * (plan.t_end)
        ob.keyframe_insert("location", frame=n)
    if plan.own_lights:
        lights = {}
    for fc_owner in [cam] + list(lights.values()) + [s.ob for s in plan.slabs] + [d[0] for d in plan.dust]:
        ad = fc_owner.animation_data
        if ad and ad.action:
            for fc in iter_fcurves(ad.action):
                for kp in fc.keyframe_points:
                    kp.interpolation = "LINEAR" if fc_owner in lights.values() or fc_owner in [d[0] for d in plan.dust] else "BEZIER"


def iter_fcurves(action):
    try:
        for fc in action.fcurves:
            yield fc
        return
    except AttributeError:
        pass
    for layer in action.layers:
        for strip in layer.strips:
            for slot in action.slots:
                cb = strip.channelbag(slot)
                if cb:
                    for fc in cb.fcurves:
                        yield fc


def same_pose(a, b, eps=1e-5):
    return (a[0] - b[0]).length < eps and abs(a[1].dot(b[1]) - 1) < eps and abs(a[2] - b[2]) < eps


def apply_frame_state(plan, f, cam, a):
    """Per frame, non keyframed state: fade attributes, fx nodes, DoF, motion blur."""
    sc = bpy.context.scene
    fx = plan.fx[f]
    for s in plan.slabs:
        if s.poses:
            s.ob["fade"] = s.poses[f][3]
            for k_, arr in getattr(s, "props", {}).items():
                s.ob[k_] = arr[f]
            s.ob.update_tag()
    if plan.per_frame:
        plan.per_frame(f)
    for nd in FX_NODES:
        nd.outputs[0].default_value = fx
    d = cam.data
    if fx > 0.002:
        d.dof.use_dof = True
        d.dof.focus_distance = plan.cam[f][2]
        d.dof.aperture_fstop = plan.fstop / max(fx, 0.05) ** 1.5
        sc.render.use_motion_blur = True
        sc.render.motion_blur_shutter = PARAMS["mb_shutter"] * fx
        sc.render.filter_size = 1.0
        if a.engine == "CYCLES":
            sc.cycles.filter_width = 1.2
            sc.cycles.use_denoising = True
            sc.cycles.use_adaptive_sampling = True
            sc.cycles.samples = a.samples or (24 if a.preview else 96)
    else:
        # the flat frame: pure emission, sampled at the pixel centres, no filter, no denoiser
        d.dof.use_dof = False
        sc.render.use_motion_blur = False
        sc.render.filter_size = 0.01
        if a.engine == "CYCLES":
            sc.cycles.filter_width = 0.01
            sc.cycles.use_denoising = False
            sc.cycles.use_adaptive_sampling = False
            sc.cycles.samples = 4
    for (dob, _v) in plan.dust:
        dob.hide_render = fx <= 0.002
    return fx


# ---------------------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------------------


class Args:
    pass


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    a = Args()
    a.move, a.layout, a.detail, a.title = "ASSEMBLE", "LIB", None, None
    a.preview, a.frames, a.step, a.still = False, None, 1, False
    a.samples, a.engine, a.seed, a.diff = None, "CYCLES", 7, False
    a.out = os.path.join(REPO, "build", "promo", "blender")
    a.sets = {}
    a.suffix = ""
    i = 0
    while i < len(argv):
        k = argv[i]
        if k in ("--move", "--layout", "--detail", "--title", "--frames", "--step", "--samples", "--engine", "--seed", "--out", "--set", "--suffix"):
            v = argv[i + 1]
            i += 2
            if k == "--move": a.move = v.upper()
            elif k == "--layout": a.layout = v
            elif k == "--detail": a.detail = v
            elif k == "--title": a.title = v
            elif k == "--frames":
                lo, _, hi = v.partition("-")
                a.frames = (int(lo), int(hi or lo))
            elif k == "--step": a.step = int(v)
            elif k == "--samples": a.samples = int(v)
            elif k == "--engine": a.engine = v.upper()
            elif k == "--seed": a.seed = int(v)
            elif k == "--suffix": a.suffix = v
            elif k == "--out": a.out = os.path.abspath(v)
            elif k == "--set":
                kk, _, vv = v.partition("=")
                a.sets[kk] = vv
        elif k in ("--preview", "--still", "--diff"):
            setattr(a, k[2:], True)
            i += 1
        else:
            raise SystemExit(f"unknown argument {k}")
    return a


def apply_sets(a):
    for k, v in a.sets.items():
        if k not in PARAMS or k == "long_overrides":
            raise SystemExit(f"unknown parameter {k}; known: {', '.join(PARAMS)}")
        PARAMS[k] = type(PARAMS[k])(v)


def diff_report(png, key):
    a = load_pixels(png, raw=True)[..., :3] * 255.0
    b = load_pixels(key, raw=True)[..., :3] * 255.0
    if a.shape != b.shape:
        print(f"[diff] size mismatch {a.shape} vs {b.shape} (use 1920x1080 renders for --diff)")
        return None
    d = np.abs(a - b)
    res = dict(mean=float(d.mean()), max=float(d.max()), p99=float(np.percentile(d, 99)))
    print(f"[diff] {os.path.basename(png)} vs {os.path.basename(key)}: mean {res['mean']:.3f}/255  p99 {res['p99']:.2f}  max {res['max']:.1f}")
    return res


def main():
    a = parse_args()
    apply_sets(a)
    P = PARAMS
    bpy.ops.wm.read_factory_settings(use_empty=True)
    FX_NODES.clear()
    SWEEP_NODES.clear()
    setup_render(a)
    lay = load_layout(a.layout)
    cam = make_camera()
    if a.move == "ASSEMBLE":
        setup_world()
        plan = plan_assemble(lay, P, a.seed)
        plan.key = lay["key"]
        dust_spec = dict(n=int(P["dust"]))
    elif a.move == "LIB2LOGO":
        setup_world()
        plan = plan_lib2logo(a, P, lay, a.seed)
        dust_spec = plan.dust_spec
    elif a.move in ("WALL2LIB", "WALL2LIB_LONG"):
        if a.move == "WALL2LIB_LONG":
            for k_, v_ in P["long_overrides"].items():
                if k_ not in a.sets:
                    P[k_] = type(PARAMS[k_])(v_)
        plan = plan_wall2lib(a, P, lay, a.seed)
        dust_spec = plan.dust_spec
    else:
        raise SystemExit(f"unknown move {a.move}")
    lights = {} if plan.own_lights else {ob.name: ob for ob in build_lights()}
    plan.dust = build_dust(seed=a.seed, **dust_spec)
    keyframe_plan(plan, cam, lights)
    sc = bpy.context.scene
    sc.frame_start, sc.frame_end = 1, plan.n
    out = os.path.join(a.out, "assemble", a.move.lower() + a.suffix + ("-preview" if a.preview else ""))
    os.makedirs(out, exist_ok=True)
    lo, hi = a.frames or (1, plan.n)
    frames = list(range(lo, hi + 1, a.step))
    if a.frames is None and frames[-1] != plan.n:
        frames.append(plan.n)
    camdata, times = [], []
    cam_path = os.path.join(out, "camera.json")
    try:
        with open(cam_path, encoding="utf-8") as fh:
            old = json.load(fh)
        camdata = [c for c in old.get("frames", []) if c["frame"] not in frames]
    except Exception:
        pass
    last_png = None
    for fr in frames:
        f = fr - 1
        sc.frame_set(fr)
        fx = apply_frame_state(plan, f, cam, a)
        path = os.path.join(out, (f"still_{fr:04d}.png" if a.still else f"{fr:04d}.png"))
        sc.render.filepath = path
        t0 = time.time()
        bpy.ops.render.render(write_still=True)
        dt = time.time() - t0
        times.append(dt)
        last_png = path
        m = cam.matrix_world
        camdata.append(dict(frame=fr, t=f / FPS, location=list(cam.location), quaternion=list(cam.rotation_quaternion),
                            matrix=[list(r) for r in m], lens=cam.data.lens, sensor=SENSOR, focus_distance=plan.cam[f][2],
                            fstop=cam.data.dof.aperture_fstop if cam.data.dof.use_dof else None, fx=fx))
        print(f"frame {fr}/{plan.n} fx={fx:.2f} {dt:.2f}s")
    camdata.sort(key=lambda c: c["frame"])
    with open(cam_path, "w", encoding="utf-8") as fh:
        json.dump(dict(fps=FPS, width=FW, height=FH, frames=camdata), fh)
    with open(os.path.join(out, "timing.txt"), "w", encoding="utf-8") as fh:
        fh.write(f"engine {a.engine}  samples {sc.cycles.samples if a.engine == 'CYCLES' else sc.eevee.taa_render_samples}"
                 f"  res {sc.render.resolution_x * sc.render.resolution_percentage // 100}x"
                 f"{sc.render.resolution_y * sc.render.resolution_percentage // 100}\n")
        fh.write(f"frames {len(times)}  mean {sum(times) / max(len(times), 1):.2f}s  max {max(times):.2f}s  total {sum(times):.1f}s\n")
    if a.diff and last_png and plan.final_key():
        diff_report(last_png, plan.final_key())


Plan.final_key = lambda self: getattr(self, "key", None)

if __name__ == "__main__":
    main()
