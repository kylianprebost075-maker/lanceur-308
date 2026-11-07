// Assemble la 308 phase 1 cinq portes à partir de l'export Sketchfab (mod BeamNG « toutes variantes »).
// Usage : node assembler_308.mjs <entrée.glb> <sortie.glb>
import { readFileSync } from 'node:fs';
import { NodeIO } from '@gltf-transform/core';
import { ALL_EXTENSIONS, KHRMaterialsEmissiveStrength } from '@gltf-transform/extensions';
import { prune, dedup, weld, simplify, simplifyPrimitive } from '@gltf-transform/functions';
import { MeshoptSimplifier } from 'meshoptimizer';

const [, , input, output] = process.argv;
const io = new NodeIO().registerExtensions(ALL_EXTENSIONS);
const doc = await io.read(input);
const root = doc.getRoot();
const sketchfabRoot = root.listScenes()[0].listChildren()[0];
const group = sketchfabRoot.listChildren()[0];
const byName = (n) => {
  const found = group.listChildren().find((c) => c.getName() === n) ?? root.listNodes().find((c) => c.getName() === n);
  if (!found) throw new Error(`pièce introuvable : ${n}`);
  return found;
};

// Pièces de la 308 de 2007 (phase 1, 5 portes). Tout le reste (moteur, GTi, restylage, intérieur) est retiré.
const KEEP = [
  'p308_body', 'p308_hood', 'p308_fender_L', 'p308_fender_R',
  'p308_door_L', 'p308_door_R', 'p308_door_RL', 'p308_door_RR',
  'p308_doorglass_L', 'p308_doorglass_R', 'p308_windshield',
  'p308_tailgate', 'p308_tailgateglass', 'p308_tailgate.004',
  'p308_bumper_F', 'p308_bumper_R', 'p308_headlight_L', 'p308_headlight_R',
  'taillights', 'p308_logo_F',
  'p308_lciense', 'lisense', // plaques avant et arrière (texture modele-3d/plaque.png, générée par plaque.html)
  // Habitacle (visible à travers les vitres et pour la vue intérieure).
  'p308_dash', 'p308_dash_premium', 'p308_seat_FL', 'p308_seat_FR', 'p308_steer', 'p308_decals_gauges',
  'p308_needle_tacho', 'p308_needle_speedo', 'p308_needle_oil', 'p308_needle_fuel',
];
// Pare-chocs phase 1 : le jeu les décale, leur géométrie est déjà à la bonne place.
const RESET = ['p308_bumper_F', 'p308_bumper_R'];

const rimSrc = byName('p308_wheel');
const tireSrc = byName('peu_308gti_11_wheel_tire');
const keep = new Set(KEEP.map(byName));
for (const n of RESET) byName(n).setTranslation([0, 0, 0]).setRotation([0, 0, 0, 1]).setScale([1, 1, 1]);

// Dans l'export BeamNG, le volant et les 4 aiguilles sont à plat (rotation appliquée en jeu par le JBeam) :
// on les cale à leur angle réel sur la colonne de direction (-72,7°) et sur le combiné d'instruments (-71,41°).
const quatAxis = (ax, ay, az, deg) => {
  const h = (deg * Math.PI) / 360, s = Math.sin(h);
  return [ax * s, ay * s, az * s, Math.cos(h)];
};
const quatMul = (a, b) => [
  a[3]*b[0] + a[0]*b[3] + a[1]*b[2] - a[2]*b[1],
  a[3]*b[1] - a[0]*b[2] + a[1]*b[3] + a[2]*b[0],
  a[3]*b[2] + a[0]*b[1] - a[1]*b[0] + a[2]*b[3],
  a[3]*b[3] - a[0]*b[0] - a[1]*b[1] - a[2]*b[2],
];
byName('p308_steer').setTranslation([0.3248, -0.3610, 0.8453]).setRotation(quatAxis(1, 0, 0, -72.7));
const qDial = quatAxis(1, 0, 0, -71.41);
for (const [nm, rz] of [['p308_needle_tacho', 130], ['p308_needle_speedo', 130], ['p308_needle_oil', -35], ['p308_needle_fuel', 35]]) {
  byName(nm).setRotation(quatMul(qDial, quatAxis(0, 0, 1, rz)));
}

// Roues : jante p308 (agrandie pour remplir le pneu) + pneu, aux 4 coins (repère voiture : x gauche, y arrière, z haut).
const copy = (n) => {
  const c = doc.createNode(n.getName()).setTranslation(n.getTranslation()).setRotation(n.getRotation()).setScale(n.getScale());
  if (n.getMesh()) c.setMesh(n.getMesh());
  n.listChildren().forEach((ch) => c.addChild(copy(ch)));
  return c;
};
const W = JSON.parse(process.env.ROUES || '{"x":0.70,"yAv":-1.25,"yAr":1.30,"z":0.42,"jante":1.12}');
const TIRE_CENTER = [1.36, -2.195, 0.635]; // centre du pneu dans son maillage d'origine
const wheels = [];
for (const [name, hubName, x, y] of [
  ['roue_AVG', 'moyeu_AVG', W.x, W.yAv],
  ['roue_AVD', 'moyeu_AVD', -W.x, W.yAv],
  ['roue_ARG', 'moyeu_ARG', W.x, W.yAr],
  ['roue_ARD', 'moyeu_ARD', -W.x, W.yAr],
]) {
  const w = doc.createNode(name).setTranslation([x, y, W.z]);
  if (x < 0) w.setRotation([0, 0, 1, 0]); // côté droit : demi-tour autour de la verticale
  const hub = doc.createNode(hubName);
  hub.addChild(copy(rimSrc).setScale([1, W.jante, W.jante]));
  hub.addChild(copy(tireSrc).setTranslation(TIRE_CENTER.map((v) => -v)));
  w.addChild(hub);
  wheels.push(w);
}
for (const c of group.listChildren()) if (!keep.has(c)) c.dispose();
wheels.forEach((w) => group.addChild(w));

// Charnières (repère voiture : x gauche, y arrière, z haut) : chaque ouvrant pivote autour de son nœud « charniere_* ».
const HINGES = {
  charniere_porte_AVG: { at: [0.84, -0.86, 0.80], parts: ['p308_door_L', 'p308_doorglass_L'] },
  charniere_porte_AVD: { at: [-0.84, -0.86, 0.80], parts: ['p308_door_R', 'p308_doorglass_R'] },
  charniere_porte_ARG: { at: [0.82, 0.15, 0.80], parts: ['p308_door_RL'] },
  charniere_porte_ARD: { at: [-0.82, 0.15, 0.80], parts: ['p308_door_RR'] },
  charniere_coffre: { at: [0, 1.32, 1.38], parts: ['p308_tailgate', 'p308_tailgateglass', 'p308_tailgate.004'] },
  charniere_capot: { at: [0, -1.02, 0.96], parts: ['p308_hood'] },
};
for (const [name, { at, parts }] of Object.entries(HINGES)) {
  const hinge = doc.createNode(name).setTranslation(at);
  for (const partName of parts) {
    const part = byName(partName);
    const t = part.getTranslation();
    group.removeChild(part);
    hinge.addChild(part.setTranslation([t[0] - at[0], t[1] - at[1], t[2] - at[2]]));
  }
  group.addChild(hinge);
}

// Matériaux : sans clearcoat (trop coûteux sur le GPU Mali-T820 de l'autoradio), peinture noire métallisée optimisée.
const emissiveStrength = doc.createExtension(KHRMaterialsEmissiveStrength);
const glow = (m) => m.setEmissiveFactor([0.0, 0.0, 0.0])
  .setExtension('KHR_materials_emissive_strength', emissiveStrength.createEmissiveStrength().setEmissiveStrength(1));
const mat = (name, rgb, metal, rough) =>
  doc.createMaterial(name).setBaseColorFactor([...rgb, 1]).setMetallicFactor(metal).setRoughnessFactor(rough).setDoubleSided(true);
const M = {
  peinture: mat('peinture_noire', [0.008, 0.010, 0.014], 0.34, 0.15),
  plastique: mat('plastique_noir', [0.012, 0.012, 0.013], 0, 0.5),
  vitre: mat('vitre_teintee', [0.02, 0.025, 0.03], 0, 0.04).setAlpha(0.42).setAlphaMode('BLEND'),
  cuir: mat('cuir_noir', [0.035, 0.035, 0.037], 0, 0.55),
  tissu: mat('tissu_gris', [0.07, 0.07, 0.075], 0, 0.95),
  habillage: mat('habillage', [0.05, 0.05, 0.055], 0, 0.7),
  aiguille: mat('aiguille', [0.95, 0.28, 0.05], 0, 0.35).setEmissiveFactor([0.85, 0.22, 0.03])
    .setExtension('KHR_materials_emissive_strength', emissiveStrength.createEmissiveStrength().setEmissiveStrength(2.2)),
  voyantFeux: glow(mat('voyant_feux', [0.04, 0.08, 0.05], 0, 0.4)),
  plaque: mat('plaque', [1, 1, 1], 0, 0.35).setBaseColorTexture(
    doc.createTexture('plaque').setImage(readFileSync(new URL('../modele-3d/plaque.png', import.meta.url))).setMimeType('image/png')),
  antibrouillard: glow(mat('antibrouillard', [0.85, 0.86, 0.88], 0.2, 0.1)),
  phareAvant: glow(mat('phare_avant', [0.95, 0.97, 1.0], 0.2, 0.06)),
  phareReflecteur: glow(mat('phare_reflecteur', [0.76, 0.79, 0.85], 0.9, 0.14)),
  vitrePhareAvant: glow(mat('vitre_phare_avant', [0.90, 0.93, 0.98], 0.05, 0.05).setAlpha(0.48).setAlphaMode('BLEND')),
  phare: mat('verre_phare', [0.8, 0.82, 0.85], 0.2, 0.12),
  chrome: mat('chrome', [0.9, 0.9, 0.92], 1, 0.08),
  aluSatine: mat('alu_satine', [0.64, 0.67, 0.72], 0.82, 0.28),
  feu: mat('feu_rouge', [0.55, 0.015, 0.02], 0, 0.15),
  feuPositionAr: glow(mat('feu_position_ar', [0.58, 0.015, 0.02], 0, 0.14)),
  recul: glow(mat('feu_blanc', [0.85, 0.85, 0.85], 0, 0.15)),
  jante: mat('jante_alu', [0.8, 0.81, 0.83], 1, 0.22),
  pneu: mat('pneu', [0.02, 0.02, 0.02], 0, 0.85),
};
const BY_MATERIAL = {
  CAR_BODY_PAINT: M.peinture, midsize_glass: M.vitre, midsize_glass_int: M.vitre, mirror: M.chrome,
  p308_headlight: M.phareAvant, p308_signal_L: M.phare, p308_signal_R: M.phare, hatch_lettering: M.chrome,
  midsize_lights: M.feu, p308_taillight: M.feuPositionAr, p308_chmsl: M.feu, p308_reverselight: M.recul,
  hubcap_0678a: M.jante, tire: M.pneu, 'licenseplate-52-11': M.plaque,
  p308_leather_1: M.cuir, p308_leather_col: M.cuir, p308_skin_wheel: M.cuir, p308_whelement: M.cuir,
  p308_seats: M.tissu, p308_panel: M.habillage, BlackFabric: M.habillage,
};
// Dans les phares et antibrouillards, « midsize_glass » est l'optique (claire) ; dans les feux arrière, elle est rouge.
const OVERRIDE = {
  p308_headlight_L: { midsize_glass: M.vitrePhareAvant, etk800: M.phareReflecteur, p308_headlight: M.phareAvant },
  p308_headlight_R: { midsize_glass: M.vitrePhareAvant, etk800: M.phareReflecteur, p308_headlight: M.phareAvant },
  p308_bumper_F: { midsize_glass: M.antibrouillard, midsize_lights: M.antibrouillard },
  taillights: { midsize_glass: M.feuPositionAr, midsize_lights: M.feuPositionAr, p308_taillight: M.feuPositionAr },
  p308_steer: { etk800: M.chrome, p308_whelement: M.aluSatine },
  p308_needle_tacho: { p308_gauges: M.aiguille },
  p308_needle_speedo: { p308_gauges: M.aiguille },
  p308_needle_oil: { p308_gauges: M.aiguille },
  p308_needle_fuel: { p308_gauges: M.aiguille },
  p308_decals_gauges: { lowbeam: M.voyantFeux },
};
const paint = (node, override) => {
  const mesh = node.getMesh();
  if (mesh) for (const p of mesh.listPrimitives()) {
    const old = (p.getMaterial()?.getName() || '').replace(/-material$/, '');
    p.setMaterial(override?.[old] || BY_MATERIAL[old] || M.plastique);
  }
  node.listChildren().forEach((c) => paint(c, override));
};
// Les maillages partagés entre pièces gardent la même couleur : on duplique ceux des pièces avec exception.
for (const c of group.listChildren()) {
  const o = OVERRIDE[c.getName()];
  if (o) (function dup(n) { if (n.getMesh()) n.setMesh(n.getMesh().clone()); n.listChildren().forEach(dup); })(c);
  paint(c, o);
}

await MeshoptSimplifier.ready;
await doc.transform(prune(), dedup(), weld(), simplify({ simplifier: MeshoptSimplifier, ratio: 0.5, error: 0.0008 }), prune());
// Les jantes sont dessinées 4 fois : on les allège davantage (détail invisible à la taille de l'écran).
const rimMeshes = new Set();
for (const wheel of root.listNodes().filter((n) => n.getName().startsWith('roue_'))) {
  (function collect(n) { const m = n.getMesh(); if (m && !m.listPrimitives().some((p) => p.getMaterial()?.getName() === 'pneu')) rimMeshes.add(m); n.listChildren().forEach(collect); })(wheel);
}
for (const mesh of rimMeshes) for (const prim of mesh.listPrimitives()) simplifyPrimitive(prim, { simplifier: MeshoptSimplifier, ratio: 0.25, error: 0.004 });
await doc.transform(prune());
await io.write(output, doc);
{
  const { getBounds } = await import('@gltf-transform/core');
  for (const n of root.listNodes()) {
    const prims = n.getMesh()?.listPrimitives().filter((p) => p.getMaterial()?.getName() === 'antibrouillard') ?? [];
    if (prims.length) console.log('antibrouillard dans', n.getName(), getBounds(n).min.map((v) => v.toFixed(2)), getBounds(n).max.map((v) => v.toFixed(2)));
  }
}
const tris = root.listMeshes().reduce((s, m) => s + m.listPrimitives().reduce((t, p) => t + (p.getIndices()?.getCount() ?? 0) / 3, 0), 0);
console.log(`OK ${output} — ${root.listNodes().length} nœuds, ${Math.round(tris)} triangles (maillages uniques)`);
