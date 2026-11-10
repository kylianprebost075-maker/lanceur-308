import copy, json, math, struct
import os
# Usage : python finaliser_car_glb.py  (depuis n'importe où)
# Entrée : modele-3d/car_base_assemblee.glb (sortie de assembler_308.mjs)
# Sortie : modele-3d/car.glb (modele personnel, avec la vraie plaque : jamais dans l'APK depuis la v0.23).
# Ensuite : python plaque_glb.py ../modele-3d/car.glb ../modele-3d/plaque-factice.png ../modele-3d/car-public.glb
# puis copier car-public.glb vers projet/lanceur-308/app/src/main/assets/models/car.glb
_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = os.path.join(_root, 'modele-3d', 'car_base_assemblee.glb')
dsts = [
    os.path.join(_root, 'modele-3d', 'car.glb'),
]

with open(src, "rb") as f:
    magic, ver, length = struct.unpack("<4sII", f.read(12))
    jlen, jtype = struct.unpack("<II", f.read(8))
    gltf = json.loads(f.read(jlen).decode("utf-8"))
    blen, btype = struct.unpack("<II", f.read(8))
    bin_chunk = f.read(blen)

assert magic == b"glTF" and ver == 2

# Quaternion helpers (glTF order: [x, y, z, w])
def quat_axis_angle(ax, ay, az, deg):
    half = math.radians(deg) * 0.5
    s = math.sin(half)
    return [ax * s, ay * s, az * s, math.cos(half)]

def quat_mul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return [
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
        aw * bw - ax * bx - ay * by - az * bz,
    ]

def quat_rotate(q, v):
    qx, qy, qz, qw = q
    vx, vy, vz = v
    # t = 2 * cross(q.xyz, v)
    tx = 2.0 * (qy * vz - qz * vy)
    ty = 2.0 * (qz * vx - qx * vz)
    tz = 2.0 * (qx * vy - qy * vx)
    return (
        vx + qw * tx + (qy * tz - qz * ty),
        vy + qw * ty + (qz * tx - qx * tz),
        vz + qw * tz + (qx * ty - qy * tx),
    )

# Verify quaternion math matches rot_x(rot_z(v, rz), rx) to 1e-9!
test_q = quat_mul(quat_axis_angle(1, 0, 0, -71.41), quat_axis_angle(0, 0, 1, 130.0))
tv = (0.01, -0.045, 0.002)
crz, srz = math.cos(math.radians(130.0)), math.sin(math.radians(130.0))
v1 = (tv[0]*crz - tv[1]*srz, tv[0]*srz + tv[1]*crz, tv[2])
crx, srx = math.cos(math.radians(-71.41)), math.sin(math.radians(-71.41))
v2 = (v1[0], v1[1]*crx - v1[2]*srx, v1[1]*srx + v1[2]*crx)
vq = quat_rotate(test_q, tv)
assert all(abs(a - b) < 1e-9 for a, b in zip(v2, vq)), f"Quat mismatch: {v2} vs {vq}"

# 1. Strip expensive KHR_materials_clearcoat (halves Mali-T820 PBR fragment shader cost)
exts = [e for e in gltf.get("extensionsUsed", []) if e != "KHR_materials_clearcoat"]
if "KHR_materials_emissive_strength" not in exts:
    exts.append("KHR_materials_emissive_strength")
gltf["extensionsUsed"] = exts
if "extensionsRequired" in gltf:
    gltf["extensionsRequired"] = [e for e in gltf["extensionsRequired"] if e != "KHR_materials_clearcoat"]

materials = gltf["materials"]
meshes = gltf["meshes"]
nodes = gltf["nodes"]

for m in materials:
    if "extensions" in m and "KHR_materials_clearcoat" in m["extensions"]:
        del m["extensions"]["KHR_materials_clearcoat"]
        if not m["extensions"]:
            del m["extensions"]

def mat_idx_by_name(name):
    for i, m in enumerate(materials):
        if m.get("name") == name:
            return i
    raise KeyError(name)

# Tune Noir Perla Nera paint for rich single-lobe metallic gloss without clearcoat
idx_paint = mat_idx_by_name("peinture_noire")
materials[idx_paint]["pbrMetallicRoughness"] = {
    "baseColorFactor": [0.008, 0.010, 0.014, 1.0],
    "metallicFactor": 0.34,
    "roughnessFactor": 0.15,
}

def make_glow_mat(name, base_rgba, metallic, roughness, alpha_mode=None, init_emissive=(0.0, 0.0, 0.0), init_strength=1.0):
    m = {
        "name": name,
        "doubleSided": True,
        "pbrMetallicRoughness": {
            "baseColorFactor": list(base_rgba),
            "metallicFactor": metallic,
            "roughnessFactor": roughness,
        },
        "emissiveFactor": list(init_emissive),
        "extensions": {
            "KHR_materials_emissive_strength": {"emissiveStrength": init_strength}
        },
    }
    if alpha_mode:
        m["alphaMode"] = alpha_mode
    materials.append(m)
    return len(materials) - 1

def make_std_mat(name, base_rgba, metallic, roughness):
    m = {
        "name": name,
        "doubleSided": True,
        "pbrMetallicRoughness": {
            "baseColorFactor": list(base_rgba),
            "metallicFactor": metallic,
            "roughnessFactor": roughness,
        },
    }
    materials.append(m)
    return len(materials) - 1

def add_glow_to_existing(idx):
    m = materials[idx]
    m["emissiveFactor"] = [0.0, 0.0, 0.0]
    m.setdefault("extensions", {})["KHR_materials_emissive_strength"] = {"emissiveStrength": 1.0}

# 2. Upgrade fog, reverse, headlights, reflectors, lenses, rear position lights, chrome lion, satin-alu wheel trim & needles
idx_fog = mat_idx_by_name("antibrouillard")
idx_rev = mat_idx_by_name("feu_blanc")
idx_chrome = mat_idx_by_name("chrome")
add_glow_to_existing(idx_fog)
add_glow_to_existing(idx_rev)

idx_phare_avant = make_glow_mat("phare_avant", [0.95, 0.97, 1.0, 1.0], metallic=0.20, roughness=0.06)
idx_phare_refl = make_glow_mat("phare_reflecteur", [0.76, 0.79, 0.85, 1.0], metallic=0.90, roughness=0.14)
idx_vitre_phare = make_glow_mat("vitre_phare_avant", [0.90, 0.93, 0.98, 0.48], metallic=0.05, roughness=0.05, alpha_mode="BLEND")
idx_feu_pos_ar = make_glow_mat("feu_position_ar", [0.58, 0.015, 0.02, 1.0], metallic=0.0, roughness=0.14)
idx_aiguille = make_glow_mat("aiguille", [0.95, 0.28, 0.05, 1.0], metallic=0.0, roughness=0.35, init_emissive=(0.85, 0.22, 0.03), init_strength=2.2)
idx_voyant_feux = make_glow_mat("voyant_feux", [0.04, 0.08, 0.05, 1.0], metallic=0.0, roughness=0.4)
idx_alu_satine = make_std_mat("alu_satine", [0.64, 0.67, 0.72, 1.0], metallic=0.82, roughness=0.28)

def set_node_material(node_idx, mat_idx, expected_sub=None):
    n = nodes[node_idx]
    if expected_sub:
        assert expected_sub in n.get("name", ""), f"Node {node_idx} name {n.get('name')} != {expected_sub}"
    old_m = n["mesh"]
    shared = sum(1 for x in nodes if x.get("mesh") == old_m) > 1
    if shared:
        new_mesh = copy.deepcopy(meshes[old_m])
        meshes.append(new_mesh)
        n["mesh"] = len(meshes) - 1
    for p in meshes[n["mesh"]]["primitives"]:
        p["material"] = mat_idx

# Left headlight (node 6 children: 7, 8, 9, 10)
set_node_material(7, idx_vitre_phare, "midsize_glass")
set_node_material(8, idx_phare_refl, "etk800")
set_node_material(10, idx_phare_avant, "p308_headlight")

# Right headlight (node 81 children: 82, 83, 84, 85)
set_node_material(82, idx_vitre_phare, "midsize_glass")
set_node_material(83, idx_phare_refl, "etk800")
set_node_material(85, idx_phare_avant, "p308_headlight")

# Rear position taillights (node 86 children: 87, 89, 90)
set_node_material(87, idx_feu_pos_ar, "midsize_glass")
set_node_material(89, idx_feu_pos_ar, "midsize_lights")
set_node_material(90, idx_feu_pos_ar, "p308_taillight")

# Interior cockpit: steering wheel chrome lion (108), satin-alu inserts (110), illuminated needles (131,133,135,137), green lowbeam (147)
set_node_material(108, idx_chrome, "etk800")
set_node_material(110, idx_alu_satine, "p308_whelement")
for needle_node in (131, 133, 135, 137):
    set_node_material(needle_node, idx_aiguille, "p308_gauges")
set_node_material(147, idx_voyant_feux, "lowbeam")

# 3. FIX STEERING WHEEL ASSEMBLY & GAUGE NEEDLES ORIENTATION!
# In BeamNG's Collada export, p308_steer (node 106) and p308_needle_* (130, 132, 134, 136) are exported flat (r=None)
# because BeamNG applies JBeam baseRotation at runtime.
# Align p308_steer (-72.7 deg around X) flush onto the steering column shroud at [0.3248, -0.3610, 0.8453]!
assert nodes[106].get("name") == "p308_steer"
nodes[106]["translation"] = [0.3248, -0.3610, 0.8453]
nodes[106]["rotation"] = [round(v, 7) for v in quat_axis_angle(1, 0, 0, -72.7)]

# Align all 4 gauge needles flush onto the -71.41 deg instrument cluster dial face at clean resting angles:
needle_fixes = [
    (130, "p308_needle_tacho", 130.0),
    (132, "p308_needle_speedo", 130.0),
    (134, "p308_needle_oil", -35.0),
    (136, "p308_needle_fuel", 35.0),
]
q_dial = quat_axis_angle(1, 0, 0, -71.41)
for n_idx, n_name, rest_z in needle_fixes:
    assert nodes[n_idx].get("name") == n_name
    q_n = quat_mul(q_dial, quat_axis_angle(0, 0, 1, rest_z))
    nodes[n_idx]["rotation"] = [round(v, 7) for v in q_n]

# 4. Drop the unseen underbody MESH (node 57 -> child 58) but keep the node attached.
# (Detaching node 57 left an orphan root node: Filament gltfio still renders it, without the
#  Sketchfab -90deg root rotation -> giant vertical slab through the roof and under the car!)
assert nodes[57].get("name") == "underbody"
for c in nodes[57].get("children", []):
    nodes[c].pop("mesh", None)
nodes[57].pop("mesh", None)

# Safety: every node must be reachable from the scene (no orphan roots rendered untransformed).
reach = set()
def _mark(i):
    reach.add(i)
    for ch in nodes[i].get("children", []):
        _mark(ch)
for r in gltf["scenes"][0]["nodes"]:
    _mark(r)
orphans = [i for i in range(len(nodes)) if i not in reach]
assert not orphans, f"Orphan nodes: {orphans}"

# 5. Add dedicated spin-pivot empty nodes moyeu_AVG, moyeu_AVD, moyeu_ARG, moyeu_ARD
wheel_defs = [
    (150, "roue_AVG", "moyeu_AVG"),
    (156, "roue_AVD", "moyeu_AVD"),
    (162, "roue_ARG", "moyeu_ARG"),
    (168, "roue_ARD", "moyeu_ARD"),
]
for w_idx, w_name, hub_name in wheel_defs:
    wn = nodes[w_idx]
    assert wn.get("name") == w_name
    old_children = list(wn["children"])
    hub_idx = len(nodes)
    nodes.append({
        "name": hub_name,
        "children": old_children,
    })
    wn["children"] = [hub_idx]

json_bytes = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
while len(json_bytes) % 4 != 0:
    json_bytes += b" "
while len(bin_chunk) % 4 != 0:
    bin_chunk += b"\x00"

total_len = 12 + 8 + len(json_bytes) + 8 + len(bin_chunk)
glb_out = (
    struct.pack("<4sII", b"glTF", 2, total_len)
    + struct.pack("<II", len(json_bytes), 0x4E4F534A)
    + json_bytes
    + struct.pack("<II", len(bin_chunk), 0x004E4942)
    + bin_chunk
)

for d in dsts:
    with open(d, "wb") as f:
        f.write(glb_out)
    print(f"Wrote {d} ({len(glb_out):,} bytes, {len(materials)} materials, {len(meshes)} meshes, {len(nodes)} nodes)")
