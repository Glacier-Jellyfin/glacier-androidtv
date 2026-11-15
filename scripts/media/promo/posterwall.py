"""Promo pilot C, "the poster wall": a dark, gently curved gallery wall of the real demo posters.

What:   Builds the whole scene in Blender (Cycles, OptiX), animates three camera moves and
        renders them, and exports the exact 2D corners of the target poster for every frame.
          P1  8 s  light sweep reveals the wall, glide along it, dive into the kids poster
                   "Year of the Fox" until it fills the frame height, centred, on the last frame.
          P2  3 s  float in front of the wall, find "Glass Mountain", dive in until it fills
                   the frame height, centred, on the last frame.
          P3  6 s  reverse: start on "Glass Mountain" filling the frame height, pull back to
                   the whole wall, lights dim, calm dark space (upper half) for a logo.
        30 fps, 1920x1080 final. Kids posters (rating up to FSK-6) sit in one block of the wall.

How:    1. python scripts/media/promo/fetch_posters.py          (once, needs the demo server)
        2. blender -b --factory-startup --python scripts/media/promo/posterwall.py -- \
               --posters build/promo/cache/posters --move all [--preview] [--frames 1-60] [--step 2]
        Blender 5.3: %LOCALAPPDATA%\\Programs\\blender-5.3.0-windows-x64\\blender.exe
        --posters DIR    folder with <slug>.jpg and index.json (fetch_posters.py)
        --move           P1, P2, P3 or all
        --preview        960x540, few samples, into wall/preview/<move>/ (target.json stays in 1920x1080 pixels)
        --frames a-b     render only these frames (1-based, inclusive); target.json always covers all frames
        --step N         render every Nth frame only
        --out DIR        override the output folder (e.g. for stills)
        --samples N      override the sample count
        --no-render      only build the scene and write target.json

Output: build/promo/blender/wall/<move>/####.png          (final, frame numbers start at 1)
        build/promo/blender/wall/preview/<move>/####.png  (--preview)
        build/promo/blender/wall/<move>/target.json
        {"move", "fps", "width", "height", "target", "corner_order": "TL,TR,BR,BL",
         "frames": [{"frame": 1, "corners": [[x, y] x4], "occluded": false, "in_frame": true}, ...]}
        Pixel coordinates in the 1920x1080 frame, origin top left, corners of the visible poster face.
        "occluded": something other than the target is in front of (parts of) it; "in_frame":
        all four corners are inside the picture.

Deterministic: fixed seeds for the layout, the jitter and Cycles.
"""
import argparse
import json
import math
import random
import sys
from pathlib import Path

import bpy
import bmesh
from bpy_extras.object_utils import world_to_camera_view
from mathutils import Matrix, Vector

# ---------------------------------------------------------------- constants

FPS = 30
FULL = (1920, 1080)
MOVES = {"P1": 8.0, "P2": 3.0, "P3": 6.0}

SENSOR_W = 36.0
SENSOR_V = SENSOR_W * FULL[1] / FULL[0]      # vertical sensor size, 20.25 mm, for "fills the frame height"

RADIUS = 8.0                      # radius of the curved wall (concave, viewer inside)
ROWS = 6
POSTER = (0.50, 0.75)             # 2:3
PITCH = (0.58, 0.83)
THICK = 0.012
FRONT = 0.03                      # front face of a normal poster, in front of the wall radius
HERO_SIZE = (1.0, 1.5)            # the hero poster covers 2x2 slots
HERO_TITLE = "Glass Mountain"
HERO_COLS_AT = 8
HERO_ROWS_AT = 2
KID_ROW_FOX = 2
SEED = 20260514

BG = (0.003, 0.0075, 0.0125)      # dark brand teal / blue (#0A1420 .. #05090F)
HAZE = (0.55, 0.85, 0.95)
BEAM = (0.86, 0.96, 1.0)          # light cyan accent


# ---------------------------------------------------------------- helpers

def smooth(t: float) -> float:
    t = min(max(t, 0.0), 1.0)
    return t * t * t * (t * (t * 6 - 15) + 10)


def spline(keys: list, t: float) -> float:
    """Cubic Hermite through (time, value) keys; zero speed at both ends, Catmull-Rom inside."""
    if t <= keys[0][0]:
        return keys[0][1]
    if t >= keys[-1][0]:
        return keys[-1][1]
    for i in range(len(keys) - 1):
        if keys[i][0] <= t <= keys[i + 1][0]:
            break
    def tangent(k):
        if k == 0 or k == len(keys) - 1:
            return 0.0
        return (keys[k + 1][1] - keys[k - 1][1]) / (keys[k + 1][0] - keys[k - 1][0])
    t0, v0 = keys[i]
    t1, v1 = keys[i + 1]
    h = t1 - t0
    u = (t - t0) / h
    m0, m1 = tangent(i) * h, tangent(i + 1) * h
    return ((2 * u ** 3 - 3 * u ** 2 + 1) * v0 + (u ** 3 - 2 * u ** 2 + u) * m0
            + (-2 * u ** 3 + 3 * u ** 2) * v1 + (u ** 3 - u ** 2) * m1)


def channels(rows: list) -> dict:
    """[(t, {name: value})] -> {name: [(t, value)]}"""
    names = rows[0][1].keys()
    return {n: [(t, v[n]) for t, v in rows] for n in names}


def fill_distance(lens: float, height: float) -> float:
    """Distance from a flat target of this height to the camera so that it fills the frame height."""
    return lens * height / SENSOR_V


# ---------------------------------------------------------------- layout

class Layout:
    """Grid slots on the curved wall. Slot (col, row) -> arc position s (m) and height h (m)."""

    def __init__(self, posters: list):
        rng = random.Random(SEED)
        self.by_title = {p["title"]: p for p in posters}
        hero = self.by_title[HERO_TITLE]
        kids = [p for p in posters if p["kids"] and p is not hero]
        fox = self.by_title.get("Year of the Fox")
        if fox is None:
            fox = max(kids, key=lambda p: p["colour"])
        others = [p for p in posters if not p["kids"] and p is not hero]
        self.cols = math.ceil((len(posters) + 3) / ROWS)
        self.hero, self.fox = hero, fox

        kid_cols = math.ceil(len(kids) / ROWS)
        kid_at = round(self.cols * 0.66)
        self.kid_cols = range(kid_at, kid_at + kid_cols)
        self.slots = {}                              # (col, row) -> (poster, is_hero)
        self.slots[(HERO_COLS_AT, HERO_ROWS_AT)] = (hero, True)
        taken = {(HERO_COLS_AT + a, HERO_ROWS_AT + b) for a in (0, 1) for b in (0, 1)}

        pool = [k for k in kids if k is not fox]
        rng.shuffle(pool)
        block = [(c, r) for c in self.kid_cols for r in range(ROWS)]
        fox_slot = (kid_at + 1, KID_ROW_FOX)
        self.slots[fox_slot] = (fox, False)
        rest = [s for s in block if s != fox_slot]
        pool_cycle = pool[:]
        while len(pool_cycle) < len(rest):           # complete the block with repeated kids posters
            extra = pool[:]
            rng.shuffle(extra)
            pool_cycle += extra
        for slot, poster in zip(rest, pool_cycle):
            self.slots[slot] = (poster, False)

        free = [(c, r) for c in range(self.cols) for r in range(ROWS)
                if (c, r) not in taken and (c, r) not in self.slots]
        rng.shuffle(others)
        pool_cycle = others[:]
        while len(pool_cycle) < len(free):
            extra = others[:]
            rng.shuffle(extra)
            pool_cycle += extra
        # fill left to right so repeats (if any) are not next to each other more than necessary
        for slot, poster in zip(free, pool_cycle):
            self.slots[slot] = (poster, False)
        self.fox_slot = fox_slot
        self.repeats = len(free) - len(others) + max(0, len(rest) - len(pool))

    def position(self, col: float, row: float) -> tuple:
        s = (col - (self.cols - 1) / 2) * PITCH[0]
        h = ((ROWS - 1) / 2 - row) * PITCH[1]
        return s, h

    @property
    def half_width(self) -> float:
        return self.cols * PITCH[0] / 2

    @property
    def hero_at(self) -> tuple:
        return self.position(HERO_COLS_AT + 0.5, HERO_ROWS_AT + 0.5)

    @property
    def fox_at(self) -> tuple:
        return self.position(*self.fox_slot)


def basis(theta: float, spin: float = 0.0) -> Matrix:
    """Rotation of a poster at angle theta: X along the wall, Y up, Z towards the viewer."""
    x = Vector((math.cos(theta), -math.sin(theta), 0.0))
    z = Vector((-math.sin(theta), -math.cos(theta), 0.0))
    y = Vector((0, 0, 1))
    m = Matrix((x, y, z)).transposed()
    return m @ Matrix.Rotation(spin, 3, "Z")


def wall_point(s: float, h: float, radius: float = RADIUS) -> Vector:
    theta = s / RADIUS
    return Vector((radius * math.sin(theta), radius * math.cos(theta), h))


# ---------------------------------------------------------------- scene building

def enable_gpu() -> None:
    prefs = bpy.context.preferences.addons["cycles"].preferences
    prefs.compute_device_type = "OPTIX"
    prefs.get_devices()
    for device in prefs.devices:
        device.use = device.type == "OPTIX" and "4070" in device.name
    if not any(d.use for d in prefs.devices):
        for device in prefs.devices:
            device.use = device.type == "OPTIX"


def make_poster_mesh(name: str, size: tuple) -> bpy.types.Mesh:
    w, h = size[0] / 2, size[1] / 2
    mesh = bpy.data.meshes.new(name)
    bm = bmesh.new()
    verts = [bm.verts.new(v) for v in [(-w, -h, 0), (w, -h, 0), (w, h, 0), (-w, h, 0),
                                       (-w, -h, THICK), (w, -h, THICK), (w, h, THICK), (-w, h, THICK)]]
    front = bm.faces.new([verts[4], verts[5], verts[6], verts[7]])
    for quad in [(0, 3, 2, 1), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)]:
        face = bm.faces.new([verts[i] for i in quad])
        face.material_index = 1
    front.material_index = 0
    uv = bm.loops.layers.uv.new("UVMap")
    for loop, co in zip(front.loops, [(0, 0), (1, 0), (1, 1), (0, 1)]):
        loop[uv].uv = co
    bm.normal_update()
    bm.to_mesh(mesh)
    bm.free()
    return mesh


def poster_material(poster: dict, folder: Path, edge: bpy.types.Material) -> bpy.types.Material:
    mat = bpy.data.materials.new(poster["slug"])
    mat.use_nodes = True
    nodes, links = mat.node_tree.nodes, mat.node_tree.links
    bsdf = nodes["Principled BSDF"]
    tex = nodes.new("ShaderNodeTexImage")
    tex.image = bpy.data.images.load(str(folder / f"{poster['slug']}.jpg"), check_existing=True)
    tex.image.colorspace_settings.name = "sRGB"
    tex.interpolation = "Cubic"
    tex.extension = "EXTEND"
    links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
    links.new(tex.outputs["Color"], bsdf.inputs["Emission Color"])
    bsdf.inputs["Emission Strength"].default_value = 0.02       # keeps the dark wall faintly readable
    bsdf.inputs["Roughness"].default_value = 0.45
    bsdf.inputs["Specular IOR Level"].default_value = 0.35
    bsdf.inputs["Coat Weight"].default_value = 0.18              # print sheen
    bsdf.inputs["Coat Roughness"].default_value = 0.22
    return mat


def paper_materials() -> tuple:
    edge = bpy.data.materials.new("paper edge")
    edge.diffuse_color = (0.8, 0.78, 0.72, 1)
    edge.use_nodes = True
    edge.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.75, 0.73, 0.68, 1)
    edge.node_tree.nodes["Principled BSDF"].inputs["Roughness"].default_value = 0.8
    back = bpy.data.materials.new("wall")
    back.use_nodes = True
    b = back.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (0.006, 0.016, 0.022, 1)
    b.inputs["Roughness"].default_value = 0.85
    return edge, back


def build_wall(layout: Layout, folder: Path) -> dict:
    """Creates the posters and the backing wall. Returns {poster title: object} of the unique targets."""
    rng = random.Random(SEED + 1)
    edge, backing = paper_materials()
    normal_mesh = make_poster_mesh("poster", POSTER)
    hero_mesh = make_poster_mesh("poster hero", HERO_SIZE)
    materials = {}
    collection = bpy.context.scene.collection
    targets = {}
    for (col, row), (poster, is_hero) in sorted(layout.slots.items()):
        s, h = layout.position(col + (0.5 if is_hero else 0), row + (0.5 if is_hero else 0))
        is_target = poster is layout.fox or is_hero
        jitter = lambda a: 0.0 if is_target else rng.uniform(-a, a)
        s += jitter(0.012)
        h += jitter(0.012)
        spin = jitter(math.radians(0.45))
        depth = FRONT if is_target else FRONT + jitter(0.012)
        mat = materials.get(poster["slug"]) or poster_material(poster, folder, edge)
        materials[poster["slug"]] = mat
        mesh = (hero_mesh if is_hero else normal_mesh).copy()
        mesh.materials.append(mat)
        mesh.materials.append(edge)
        obj = bpy.data.objects.new(poster["title"], mesh)
        theta = s / RADIUS
        # the mesh front is at local z=THICK; shift so the front face lies at RADIUS - depth
        obj.matrix_world = Matrix.Translation(wall_point(s, h, RADIUS - depth)) @ basis(theta, spin).to_4x4() \
            @ Matrix.Translation((0, 0, -THICK))
        collection.objects.link(obj)
        if is_target:
            targets[poster["title"]] = obj
    # backing wall
    half = layout.half_width + 0.12
    top = ROWS * PITCH[1] / 2 + 0.12
    mesh = bpy.data.meshes.new("backing")
    bm = bmesh.new()
    segments = 96
    rows_v = []
    for ring_h in (-top, top):
        rows_v.append([bm.verts.new(((RADIUS + 0.05) * math.sin(a), (RADIUS + 0.05) * math.cos(a), ring_h))
                       for a in [(-half + 2 * half * i / segments) / RADIUS for i in range(segments + 1)]])
    for i in range(segments):
        bm.faces.new([rows_v[0][i + 1], rows_v[0][i], rows_v[1][i], rows_v[1][i + 1]])
    bm.normal_update()
    bm.to_mesh(mesh)
    bm.free()
    mesh.materials.append(backing)
    wall = bpy.data.objects.new("wall", mesh)
    collection.objects.link(wall)
    return targets


def add_dust(layout: Layout, count: int = 320) -> None:
    """Faint dust motes floating in the beam (P1 and P2 only). Static in the world, so the camera move gives parallax;
    kept out of the corridor in front of the two dive targets so they stay clean."""
    rng = random.Random(SEED + 2)
    mesh = bpy.data.meshes.new("mote")
    bm = bmesh.new()
    bmesh.ops.create_icosphere(bm, subdivisions=1, radius=0.009)
    bm.to_mesh(mesh)
    bm.free()
    mat = bpy.data.materials.new("mote")
    mat.use_nodes = True
    node = mat.node_tree.nodes["Principled BSDF"]
    node.inputs["Base Color"].default_value = (0.8, 0.92, 1.0, 1)
    node.inputs["Emission Color"].default_value = (0.8, 0.92, 1.0, 1)
    node.inputs["Emission Strength"].default_value = 0.35
    mesh.materials.append(mat)
    avoid = [layout.hero_at[0], layout.fox_at[0]]
    made = 0
    while made < count:
        s = rng.uniform(-layout.half_width, layout.half_width)
        dist = rng.uniform(1.6, 9.0)
        h = rng.uniform(-2.8, 3.4)
        if any(abs(s - a) < 1.6 and dist < 5.0 for a in avoid):
            continue
        theta = s / RADIUS
        obj = bpy.data.objects.new("mote", mesh)
        obj.location = ((RADIUS - dist) * math.sin(theta), (RADIUS - dist) * math.cos(theta), h)
        obj.visible_shadow = False
        bpy.context.scene.collection.objects.link(obj)
        made += 1


def setup_world() -> None:
    world = bpy.data.worlds.new("night")
    world.use_nodes = True
    nodes, links = world.node_tree.nodes, world.node_tree.links
    nodes["Background"].inputs["Color"].default_value = (*BG, 1)
    nodes["Background"].inputs["Strength"].default_value = 1.0
    volume = nodes.new("ShaderNodeVolumeScatter")
    volume.inputs["Color"].default_value = (*HAZE, 1)
    volume.inputs["Density"].default_value = 0.02
    volume.inputs["Anisotropy"].default_value = 0.45
    links.new(volume.outputs["Volume"], nodes["World Output"].inputs["Volume"])
    bpy.context.scene.world = world


def make_light(name: str, kind: str, colour: tuple, size: float, blend: float) -> bpy.types.Object:
    data = bpy.data.lights.new(name, kind)
    data.color = colour
    data.energy = 0.0
    data.shadow_soft_size = size
    if kind == "SPOT":
        data.spot_size = math.radians(50)
        data.spot_blend = blend
    obj = bpy.data.objects.new(name, data)
    bpy.context.scene.collection.objects.link(obj)
    obj.rotation_mode = "QUATERNION"
    return obj


def setup_render(preview: bool, samples: int | None) -> None:
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    enable_gpu()
    cycles = scene.cycles
    cycles.device = "GPU"
    cycles.samples = samples or (24 if preview else 96)
    cycles.use_adaptive_sampling = True
    cycles.adaptive_threshold = 0.02 if preview else 0.008
    cycles.use_denoising = True
    cycles.denoiser = "OPTIX"
    cycles.seed = SEED
    cycles.use_animated_seed = False
    cycles.max_bounces = 4
    cycles.diffuse_bounces = 3
    cycles.glossy_bounces = 3
    cycles.volume_bounces = 1
    cycles.transparent_max_bounces = 4
    cycles.volume_step_rate = 1.0
    cycles.volume_max_steps = 256
    cycles.sample_clamp_indirect = 8.0
    scene.render.resolution_x, scene.render.resolution_y = (960, 540) if preview else FULL
    scene.render.resolution_percentage = 100
    scene.render.fps = FPS
    scene.render.use_motion_blur = True
    scene.render.motion_blur_shutter = 0.5
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGB"
    scene.render.image_settings.color_depth = "8"
    scene.render.film_transparent = False
    scene.view_settings.view_transform = "AgX"
    for look in ("AgX - Medium High Contrast", "Medium High Contrast"):
        try:
            scene.view_settings.look = look
            break
        except TypeError:
            continue
    scene.view_settings.exposure = 0.0


# ---------------------------------------------------------------- the moves

def look_quat(origin: Vector, target: Vector):
    return (target - origin).to_track_quat("-Z", "Y")


class Move:
    """Camera and light animation of one move, as spline channels over t in 0..1."""

    def __init__(self, name: str, layout: Layout):
        self.name = name
        self.layout = layout
        self.frames = round(MOVES[name] * FPS)
        hs, hh = layout.hero_at
        fs, fh = layout.fox_at
        lens_end = 50.0
        if name == "P1":
            d_end = fill_distance(lens_end, POSTER[1]) + FRONT
            rows = [
                # t     cam arc, dist, cam h, look arc, look h, lens, key arc, key h, key, wash, key size
                (0.00, dict(sc=-4.9, d=5.2, hc=0.15, sl=-3.6, hl=0.0, lens=35, ks=-5.8, kh=0.3, ki=0.0, wi=0.0, kz=44)),
                (0.12, dict(sc=-4.5, d=5.1, hc=0.15, sl=-3.2, hl=0.0, lens=35, ks=-5.0, kh=0.3, ki=1.0, wi=0.0, kz=44)),
                (0.36, dict(sc=-1.0, d=4.5, hc=0.30, sl=0.3, hl=0.05, lens=38, ks=-0.3, kh=0.2, ki=1.0, wi=0.05, kz=44)),
                (0.62, dict(sc=fs - 2.0, d=4.0, hc=fh + 0.3, sl=fs - 0.6, hl=fh + 0.1, lens=42, ks=fs - 0.4, kh=fh, ki=1.0, wi=0.12, kz=28)),
                (0.82, dict(sc=fs - 0.45, d=2.8, hc=fh + 0.1, sl=fs - 0.1, hl=fh, lens=46, ks=fs, kh=fh, ki=1.0, wi=0.1, kz=22)),
                (1.00, dict(sc=fs, d=d_end, hc=fh, sl=fs, hl=fh, lens=lens_end, ks=fs, kh=fh, ki=1.0, wi=0.12, kz=20)),
            ]
            self.target = layout.fox
        elif name == "P2":
            d_end = fill_distance(lens_end, HERO_SIZE[1]) + FRONT
            rows = [
                (0.00, dict(sc=hs - 3.4, d=7.2, hc=hh + 0.2, sl=hs - 2.2, hl=hh, lens=35, ks=hs - 2.6, kh=hh, ki=1.0, wi=0.15, kz=44)),
                (0.50, dict(sc=hs - 1.3, d=5.6, hc=hh + 0.1, sl=hs - 0.5, hl=hh, lens=40, ks=hs - 0.6, kh=hh, ki=1.0, wi=0.12, kz=32)),
                (1.00, dict(sc=hs, d=d_end, hc=hh, sl=hs, hl=hh, lens=lens_end, ks=hs, kh=hh, ki=1.0, wi=0.2, kz=32)),
            ]
            self.target = layout.hero
        else:
            d_start = fill_distance(lens_end, HERO_SIZE[1]) + FRONT
            rows = [
                (0.00, dict(sc=hs, d=d_start, hc=hh, sl=hs, hl=hh, lens=lens_end, ks=hs, kh=hh, ki=1.0, wi=0.2, kz=32)),
                (0.25, dict(sc=hs * 0.85, d=6.0, hc=hh, sl=hs * 0.85, hl=hh * 0.9, lens=46, ks=hs * 0.6, kh=hh, ki=1.0, wi=0.8, kz=58)),
                (0.60, dict(sc=hs * 0.3, d=13.0, hc=0.2, sl=hs * 0.25, hl=0.1, lens=38, ks=-0.5, kh=0.2, ki=0.6, wi=1.0, kz=60)),
                (1.00, dict(sc=0.0, d=17.5, hc=0.3, sl=0.0, hl=0.0, lens=35, ks=1.0, kh=0.3, ki=0.3, wi=0.45, kz=60)),
            ]
            self.target = layout.hero
        self.ch = channels(rows)

    def value(self, name: str, t: float) -> float:
        return spline(self.ch[name], t)

    def dim(self, t: float) -> float:
        """P3 only: overall light level, dimming slowly towards the end."""
        if self.name != "P3":
            return 1.0
        return 1.0 - 0.6 * smooth((t - 0.45) / 0.55)

    def shift(self, t: float) -> float:
        """P3 only: push the picture down so the wall sits in the lower part of the frame."""
        return 2.1 * smooth((t - 0.3) / 0.7) if self.name == "P3" else 0.0


def animate(move: Move) -> dict:
    scene = bpy.context.scene
    scene.frame_start, scene.frame_end = 1, move.frames
    cam_data = bpy.data.cameras.new("cam")
    cam_data.sensor_fit = "AUTO"
    cam_data.sensor_width = SENSOR_W
    cam_data.dof.use_dof = True
    cam_data.dof.aperture_fstop = 1.6
    cam_data.dof.aperture_blades = 7
    cam_data.clip_start, cam_data.clip_end = 0.1, 100
    cam = bpy.data.objects.new("cam", cam_data)
    cam.rotation_mode = "QUATERNION"
    scene.collection.objects.link(cam)
    scene.camera = cam
    key = make_light("key", "SPOT", BEAM, 0.35, 1.0)
    wash = make_light("wash", "SPOT", (0.8, 0.95, 1.0), 0.6, 1.0)
    wash.data.spot_size = math.radians(75)

    for frame in range(1, move.frames + 1):
        t = (frame - 1) / (move.frames - 1)
        v = {n: move.value(n, t) for n in move.ch}
        theta_c = v["sc"] / RADIUS
        cam_pos = Vector(((RADIUS - v["d"]) * math.sin(theta_c), (RADIUS - v["d"]) * math.cos(theta_c), v["hc"]))
        look = wall_point(v["sl"], v["hl"])
        cam.location = cam_pos
        cam.rotation_quaternion = look_quat(cam_pos, look)
        cam_data.lens = v["lens"]
        cam_data.dof.focus_distance = (look - cam_pos).length
        cam_data.shift_y = move.shift(t) / (v["d"] * SENSOR_W / v["lens"])
        for path in ("location", "rotation_quaternion"):
            cam.keyframe_insert(path, frame=frame)
        cam_data.keyframe_insert("lens", frame=frame)
        cam_data.keyframe_insert("shift_y", frame=frame)
        cam_data.dof.keyframe_insert("focus_distance", frame=frame)

        dim = move.dim(t)
        # key spot: beam from above and in front, sweeping across the wall
        aim = wall_point(v["ks"], v["kh"])
        theta_k = (v["ks"] - 1.2) / RADIUS
        key_pos = Vector(((RADIUS - 7.5) * math.sin(theta_k), (RADIUS - 7.5) * math.cos(theta_k), v["kh"] + 3.2))
        key.location = key_pos
        key.rotation_quaternion = look_quat(key_pos, aim)
        key.data.energy = 5200.0 * v["ki"] * dim
        key.data.spot_size = math.radians(v["kz"] * 0.58)
        # wash: soft light from beside the camera towards where it looks, scaled with distance
        wash_pos = cam_pos + Vector((0, 0, 0.6))
        dist = (look - wash_pos).length
        wash.location = wash_pos
        wash.rotation_quaternion = look_quat(wash_pos, look)
        wash.data.energy = 13.0 * dist ** 2 * v["wi"] * dim * (1.0 if move.name != "P1" else 1.0)
        for obj in (key, wash):
            obj.keyframe_insert("location", frame=frame)
            obj.keyframe_insert("rotation_quaternion", frame=frame)
            obj.data.keyframe_insert("energy", frame=frame)
        key.data.keyframe_insert("spot_size", frame=frame)
    return {"cam": cam}


# ---------------------------------------------------------------- target export

def export_target(move: Move, target_obj, cam, out: Path) -> None:
    scene = bpy.context.scene
    w, h = target_obj.data.vertices[0].co.x, target_obj.data.vertices[0].co.y
    corners_local = [Vector((-abs(w), abs(h), THICK)), Vector((abs(w), abs(h), THICK)),
                     Vector((abs(w), -abs(h), THICK)), Vector((-abs(w), -abs(h), THICK))]
    result = []
    for frame in range(1, move.frames + 1):
        scene.frame_set(frame)
        depsgraph = bpy.context.evaluated_depsgraph_get()
        corners = [target_obj.matrix_world @ c for c in corners_local]
        center = sum(corners, Vector()) / 4
        pixels, inside = [], True
        for c in corners:
            p = world_to_camera_view(scene, cam, c)
            pixels.append([round(p.x * FULL[0], 2), round((1 - p.y) * FULL[1], 2)])
            inside &= -1e-4 <= p.x <= 1 + 1e-4 and -1e-4 <= p.y <= 1 + 1e-4 and p.z > 0
        occluded = False
        origin = cam.matrix_world.translation
        for c in corners + [center]:
            point = center + (c - center) * 0.97 if c is not center else c
            direction = point - origin
            hit, loc, _n, _i, obj, _m = scene.ray_cast(depsgraph, origin, direction.normalized(), distance=direction.length - 0.002)
            if hit and obj is not target_obj:
                occluded = True
                break
        result.append({"frame": frame, "corners": pixels, "occluded": occluded, "in_frame": bool(inside)})
    out.mkdir(parents=True, exist_ok=True)
    data = {"move": move.name, "fps": FPS, "width": FULL[0], "height": FULL[1], "target": move.target["title"],
            "corner_order": "TL,TR,BR,BL", "frames": result}
    (out / "target.json").write_text(json.dumps(data), encoding="utf-8")


# ---------------------------------------------------------------- main

def load_posters(folder: Path) -> list:
    import numpy
    posters = json.loads((folder / "index.json").read_text(encoding="utf-8"))
    for p in posters:
        img = bpy.data.images.load(str(folder / f"{p['slug']}.jpg"))
        img.scale(20, 30)
        px = numpy.empty(20 * 30 * 4, dtype=numpy.float32)
        img.pixels.foreach_get(px)
        rgb = px.reshape(-1, 4)[:, :3]
        p["colour"] = float(((rgb.max(axis=1) - rgb.min(axis=1)) * rgb.max(axis=1)).mean())   # saturation x value
        bpy.data.images.remove(img)
    return posters


def run(move_name: str, args) -> None:
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    folder = Path(args.posters).resolve()
    posters = load_posters(folder)
    layout = Layout(posters)
    print(f"[{move_name}] posters: {len(posters)}, kids: {sum(p['kids'] for p in posters)}, "
          f"columns: {layout.cols}, repeats: {layout.repeats}")
    setup_render(args.preview, args.samples)
    setup_world()
    targets = build_wall(layout, folder)
    if move_name != "P3":                  # the P3 pull-back and its calm dark end stay free of motes
        add_dust(layout)
    move = Move(move_name, layout)
    cam = animate(move)["cam"]
    base = Path(args.base).resolve() / "blender" / "wall"
    target_obj = targets[move.target["title"]]
    export_target(move, target_obj, cam, base / move_name)
    if args.no_render:
        return
    first, last = 1, move.frames
    if args.frames:
        a, _, b = args.frames.partition("-")
        first, last = int(a), int(b or a)
    scene.frame_start, scene.frame_end, scene.frame_step = first, min(last, move.frames), args.step
    out = Path(args.out).resolve() if args.out else (base / "preview" / move_name if args.preview else base / move_name)
    out.mkdir(parents=True, exist_ok=True)
    scene.render.filepath = str(out / "####")
    import time
    start = time.time()
    bpy.ops.render.render(animation=True)
    count = len(range(first, min(last, move.frames) + 1, args.step))
    print(f"[{move_name}] rendered {count} frames in {time.time() - start:.1f}s ({(time.time() - start) / count:.2f}s/frame)")


def main() -> None:
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--posters", default=str(Path(__file__).resolve().parents[3] / "build/promo/cache/posters"))
    parser.add_argument("--move", default="all", choices=["P1", "P2", "P3", "all"])
    parser.add_argument("--preview", action="store_true")
    parser.add_argument("--frames")
    parser.add_argument("--step", type=int, default=1)
    parser.add_argument("--out")
    parser.add_argument("--samples", type=int)
    parser.add_argument("--no-render", action="store_true")
    parser.add_argument("--base", default=str(Path(__file__).resolve().parents[3] / "build/promo"))
    args = parser.parse_args(argv)
    for name in (list(MOVES) if args.move == "all" else [args.move]):
        run(name, args)


if __name__ == "__main__":
    main()
