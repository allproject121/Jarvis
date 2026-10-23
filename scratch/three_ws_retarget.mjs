// ../../src/glb-canonicalize.js
import { Matrix4, Quaternion, Vector3 } from "three";
var GLB_MAGIC = 1179937895;
var CHUNK_TYPE_JSON = 1313821514;
var ORIENT_EPS = 1e-5;
var ORIENT_WORLD_EPS = 1e-4;
var CANONICAL_BONES = Object.freeze([
  "Hips",
  "Spine",
  "Spine1",
  "Spine2",
  "Neck",
  "Head",
  "LeftShoulder",
  "LeftArm",
  "LeftForeArm",
  "LeftHand",
  "LeftHandIndex1",
  "LeftHandIndex2",
  "LeftHandIndex3",
  "LeftHandMiddle1",
  "LeftHandMiddle2",
  "LeftHandMiddle3",
  "LeftHandPinky1",
  "LeftHandPinky2",
  "LeftHandPinky3",
  "LeftHandRing1",
  "LeftHandRing2",
  "LeftHandRing3",
  "LeftHandThumb1",
  "LeftHandThumb2",
  "LeftHandThumb3",
  "RightShoulder",
  "RightArm",
  "RightForeArm",
  "RightHand",
  "RightHandIndex1",
  "RightHandIndex2",
  "RightHandIndex3",
  "RightHandMiddle1",
  "RightHandMiddle2",
  "RightHandMiddle3",
  "RightHandPinky1",
  "RightHandPinky2",
  "RightHandPinky3",
  "RightHandRing1",
  "RightHandRing2",
  "RightHandRing3",
  "RightHandThumb1",
  "RightHandThumb2",
  "RightHandThumb3",
  "LeftUpLeg",
  "LeftLeg",
  "LeftFoot",
  "LeftToeBase",
  "RightUpLeg",
  "RightLeg",
  "RightFoot",
  "RightToeBase"
]);
var LOOKUP = (() => {
  const m = /* @__PURE__ */ new Map();
  for (const canonical of CANONICAL_BONES) {
    m.set(canonical.toLowerCase(), canonical);
  }
  return m;
})();
var UNREAL_ALIASES = new Map(Object.entries({
  pelvis: "Hips",
  neck01: "Neck",
  claviclel: "LeftShoulder",
  upperarml: "LeftArm",
  lowerarml: "LeftForeArm",
  handl: "LeftHand",
  clavicler: "RightShoulder",
  upperarmr: "RightArm",
  lowerarmr: "RightForeArm",
  handr: "RightHand",
  thighl: "LeftUpLeg",
  calfl: "LeftLeg",
  footl: "LeftFoot",
  balll: "LeftToeBase",
  thighr: "RightUpLeg",
  calfr: "RightLeg",
  footr: "RightFoot",
  ballr: "RightToeBase"
}));
for (const [uf, cf] of [
  ["thumb", "Thumb"],
  ["index", "Index"],
  ["middle", "Middle"],
  ["ring", "Ring"],
  ["pinky", "Pinky"]
]) {
  for (let n = 1; n <= 3; n++) {
    UNREAL_ALIASES.set(`${uf}0${n}l`, `LeftHand${cf}${n}`);
    UNREAL_ALIASES.set(`${uf}0${n}r`, `RightHand${cf}${n}`);
  }
}
var EXTRA_ALIASES = (() => {
  const m = /* @__PURE__ */ new Map();
  const norm = (s) => s.replace(/[-_.\s]+/g, "").toLowerCase();
  const put = (variant, canonical) => {
    const k = norm(variant);
    if (!m.has(k)) m.set(k, canonical);
  };
  const VRM = [
    ["J_Bip_C_Hips", "Hips"],
    ["J_Bip_C_Spine", "Spine"],
    ["J_Bip_C_Chest", "Spine1"],
    ["J_Bip_C_UpperChest", "Spine2"],
    ["J_Bip_C_Neck", "Neck"],
    ["J_Bip_C_Head", "Head"],
    ["J_Bip_L_Shoulder", "LeftShoulder"],
    ["J_Bip_L_UpperArm", "LeftArm"],
    ["J_Bip_L_LowerArm", "LeftForeArm"],
    ["J_Bip_L_Hand", "LeftHand"],
    ["J_Bip_R_Shoulder", "RightShoulder"],
    ["J_Bip_R_UpperArm", "RightArm"],
    ["J_Bip_R_LowerArm", "RightForeArm"],
    ["J_Bip_R_Hand", "RightHand"],
    ["J_Bip_L_UpperLeg", "LeftUpLeg"],
    ["J_Bip_L_LowerLeg", "LeftLeg"],
    ["J_Bip_L_Foot", "LeftFoot"],
    ["J_Bip_L_ToeBase", "LeftToeBase"],
    ["J_Bip_L_Toes", "LeftToeBase"],
    ["J_Bip_R_UpperLeg", "RightUpLeg"],
    ["J_Bip_R_LowerLeg", "RightLeg"],
    ["J_Bip_R_Foot", "RightFoot"],
    ["J_Bip_R_ToeBase", "RightToeBase"],
    ["J_Bip_R_Toes", "RightToeBase"]
  ];
  for (const [v, c] of VRM) put(v, c);
  for (const [vf, cf] of [["Thumb", "Thumb"], ["Index", "Index"], ["Middle", "Middle"], ["Ring", "Ring"], ["Little", "Pinky"]]) {
    for (let n = 1; n <= 3; n++) {
      put(`J_Bip_L_${vf}${n}`, `LeftHand${cf}${n}`);
      put(`J_Bip_R_${vf}${n}`, `RightHand${cf}${n}`);
    }
  }
  for (const [v, c] of [
    ["\u30BB\u30F3\u30BF\u30FC", "Hips"],
    ["\u4E0B\u534A\u8EAB", "Hips"],
    ["\u4E0A\u534A\u8EAB", "Spine"],
    ["\u4E0A\u534A\u8EAB2", "Spine1"],
    ["\u9996", "Neck"],
    ["\u982D", "Head"]
  ]) put(v, c);
  for (const [jp, side] of [["\u5DE6", "Left"], ["\u53F3", "Right"]]) {
    for (const [v, c] of [
      ["\u80A9", "Shoulder"],
      ["\u8155", "Arm"],
      ["\u3072\u3058", "ForeArm"],
      ["\u624B\u9996", "Hand"],
      ["\u8DB3", "UpLeg"],
      ["\u3072\u3056", "Leg"],
      ["\u8DB3\u9996", "Foot"],
      ["\u3064\u307E\u5148", "ToeBase"]
    ]) put(`${jp}${v}`, `${side}${c}`);
    for (const [jp2, cf] of [["\u4EBA\u6307", "Index"], ["\u4EBA\u5DEE\u6307", "Index"], ["\u4E2D\u6307", "Middle"], ["\u85AC\u6307", "Ring"], ["\u5C0F\u6307", "Pinky"]]) {
      for (let n = 1; n <= 3; n++) put(`${jp}${jp2}${n}`, `${side}Hand${cf}${n}`);
    }
    for (let n = 0; n <= 2; n++) put(`${jp}\u89AA\u6307${n}`, `${side}HandThumb${n + 1}`);
  }
  for (const [v, c] of [
    ["chest", "Spine1"],
    ["lowerChest", "Spine1"],
    ["chestLower", "Spine1"],
    ["upperChest", "Spine2"],
    ["chestUpper", "Spine2"],
    ["abdomen", "Spine"],
    ["abdomenLower", "Spine"],
    ["abdomenUpper", "Spine1"],
    ["hip", "Hips"],
    ["lowerNeck", "Neck"],
    ["upperNeck", "Neck"],
    ["neckLower", "Neck"],
    ["neckUpper", "Neck"],
    // Reallusion CC3/CC4 splits the neck into twist joints; either drives the head chain.
    ["neckTwist01", "Neck"],
    ["neckTwist02", "Neck"],
    // Roblox R15 (`LowerTorso`/`UpperTorso`) and R6 (`Torso`), plus the generic
    // single-torso spellings simple rigs use. The pelvis IS the lower torso in
    // R15, and the chest maps to Spine1 like VRM's `chest`.
    ["torso", "Spine"],
    ["waist", "Spine"],
    ["upperTorso", "Spine1"],
    ["torsoUpper", "Spine1"],
    ["lowerTorso", "Hips"],
    ["torsoLower", "Hips"],
    ["ribcage", "Spine1"],
    ["ribs", "Spine1"],
    // Second Life / OpenSim centre chain (`m`-prefixed body bones).
    ["mPelvis", "Hips"],
    ["mTorso", "Spine"],
    ["mChest", "Spine1"],
    ["mNeck", "Neck"],
    ["mHead", "Head"],
    // Advanced Skeleton (Maya) tags centre bones with an `_M` side token:
    // `Root_M`, `Spine1_M`, `Spine2_M`, `Chest_M`, `Neck_M`, `Head_M`. Its LIMBS
    // use `_L`/`_R` and already resolve through the sided table, which is why
    // these rigs arrived with working arms and legs but no Hips — and no Hips
    // means the retargeter has no root to anchor to, so nothing animated at all.
    // AS numbers its spine from 1 at the base, one deeper than the canonical
    // chain, hence the shift (`Spine1_M` → Spine, `Spine2_M` → Spine1).
    ["root_M", "Hips"],
    ["hip_M", "Hips"],
    ["pelvis_M", "Hips"],
    ["spine1_M", "Spine"],
    ["spine2_M", "Spine1"],
    ["spine3_M", "Spine2"],
    ["chest_M", "Spine2"],
    ["neck_M", "Neck"],
    ["head_M", "Head"],
    // A zero-indexed spine base (`j_spine_0`, `spine_00`) is the first spine
    // joint; the `_1`/`_2` links already resolve as Spine1/Spine2.
    ["spine0", "Spine"],
    // UniGLTF/VRM-converter root joint.
    ["hipMaster", "Hips"],
    // Anatomical-Latin rigs. Scan pipelines, medical/anatomy kits, and the
    // ZBrush/Blender anatomy libraries name every joint for the bone itself
    // rather than for its role: `pelvis` (already mapped via the Unreal table),
    // `lumbar`, `thoracic`, `sternum`, `cervical`, `cranium`, with `humerus.L`,
    // `femur.L` and friends on the limbs below. The whole convention previously
    // mapped 3 of 52 joints and animated nothing at all.
    ["lumbar", "Spine"],
    ["thoracic", "Spine1"],
    ["sternum", "Spine2"],
    ["cervical", "Neck"],
    ["cranium", "Head"],
    ["skull", "Head"]
  ]) put(v, c);
  const SIDED = [
    ["leftUpperArm", "LeftArm"],
    ["lUpperArm", "LeftArm"],
    ["shoulderL", "LeftArm"],
    ["lShldr", "LeftArm"],
    ["lShldrBend", "LeftArm"],
    ["leftLowerArm", "LeftForeArm"],
    ["lLowerArm", "LeftForeArm"],
    ["elbowL", "LeftForeArm"],
    ["lForeArm", "LeftForeArm"],
    ["lForearmBend", "LeftForeArm"],
    // Rigify/Blender anatomical spelling (`forearm.L`, `upper_arm.L`) — the side
    // is a `.L`/`.R` SUFFIX, so it normalises to `forearml`, which none of the
    // side-PREFIX spellings above (`lForeArm`, `lLowerArm`) reach. Without this
    // the elbow bone drops and every Rigify-rigged character (Quaternius, many
    // Blender exports) animates with a rigid, unbending forearm.
    ["forearmL", "LeftForeArm"],
    ["upperArmL", "LeftArm"],
    ["wristL", "LeftHand"],
    ["lHand", "LeftHand"],
    ["lCollar", "LeftShoulder"],
    ["collarL", "LeftShoulder"],
    ["lClavicle", "LeftShoulder"],
    ["leftUpperLeg", "LeftUpLeg"],
    ["lUpperLeg", "LeftUpLeg"],
    ["hipL", "LeftUpLeg"],
    ["lThigh", "LeftUpLeg"],
    ["lThighBend", "LeftUpLeg"],
    ["leftLowerLeg", "LeftLeg"],
    ["lLowerLeg", "LeftLeg"],
    ["kneeL", "LeftLeg"],
    ["shinL", "LeftLeg"],
    ["lShin", "LeftLeg"],
    ["lCalf", "LeftLeg"],
    ["ankleL", "LeftFoot"],
    ["lFoot", "LeftFoot"],
    ["leftToes", "LeftToeBase"],
    ["toeL", "LeftToeBase"],
    ["lToe", "LeftToeBase"],
    ["lToeBase", "LeftToeBase"],
    // Generic side-prefix auto-riggers (some Meshy/Tripo + simple rigs) emit the
    // canonical short bone name behind a bare `L_`/`R_` side token: `L_Arm`,
    // `L_Leg`, `L_UpLeg`, `L_Shoulder`. The right twin is derived by the l→r rule.
    ["lArm", "LeftArm"],
    ["lShoulder", "LeftShoulder"],
    ["lLeg", "LeftLeg"],
    ["lUpLeg", "LeftUpLeg"],
    // SMPL / SMPL-X and other research-pipeline skeletons (text-to-avatar
    // generators, mocap tooling) spell every limb joint as a side word plus the
    // anatomical joint: `left_hip`, `left_knee`, `left_ankle`, `left_elbow`,
    // `left_wrist`, `left_collar`. The same spellings appear in hand-built
    // `leftKnee`-style rigs. Without these the whole lower body drops and a
    // SMPL-derived avatar glides around on frozen legs.
    ["leftHip", "LeftUpLeg"],
    ["leftThigh", "LeftUpLeg"],
    ["leftKnee", "LeftLeg"],
    ["leftShin", "LeftLeg"],
    ["leftCalf", "LeftLeg"],
    ["leftAnkle", "LeftFoot"],
    ["leftElbow", "LeftForeArm"],
    ["leftWrist", "LeftHand"],
    ["leftCollar", "LeftShoulder"],
    ["leftClavicle", "LeftShoulder"],
    ["leftToe", "LeftToeBase"],
    // Second Life / OpenSim limb chain: `m`-prefixed with a trailing side word
    // (`mCollarLeft`, `mShoulderLeft`, `mKneeLeft`). `mShoulder` is the upper
    // arm (the clavicle is `mCollar`), and the ankle is the articulating foot
    // joint; `mFoot` (the ball, between ankle and toe) drives the toe bone.
    ["mCollarLeft", "LeftShoulder"],
    ["mShoulderLeft", "LeftArm"],
    ["mElbowLeft", "LeftForeArm"],
    ["mWristLeft", "LeftHand"],
    ["mHipLeft", "LeftUpLeg"],
    ["mKneeLeft", "LeftLeg"],
    ["mAnkleLeft", "LeftFoot"],
    ["mFootLeft", "LeftToeBase"],
    ["mToeLeft", "LeftToeBase"],
    // Side-SUFFIX leg spellings (`UpperLeg.L`, `LowerLeg.R`, `LowerArm.L`). The
    // arm twins were added with the Rigify forearm fix, but the LEG twins were
    // not, and the side-PREFIX entries above (`leftUpperLeg`, `lUpperLeg`) never
    // reach `upperlegl`. Found by scripts/audit-rig-coverage.mjs against real
    // stored avatars: a Blender-exported rig using this convention lost BOTH leg
    // joints on each side and animated gliding on frozen legs.
    ["upperLegL", "LeftUpLeg"],
    ["lowerLegL", "LeftLeg"],
    ["lowerArmL", "LeftForeArm"],
    // The scapula is the clavicle in Advanced Skeleton / Daz-derived rigs.
    ["scapulaL", "LeftShoulder"],
    // Bare side-PREFIX anatomical joints (`j_L_hip`, `L_knee`, `R_ankle`). The
    // suffix twins (`hipL`, `kneeL`, `ankleL`) exist above but never reach the
    // prefix spelling. Same audit finding as the `j_` strip: Sketchfab-exported
    // rigs using this convention mapped zero bones.
    ["lHip", "LeftUpLeg"],
    ["lKnee", "LeftLeg"],
    ["lAnkle", "LeftFoot"],
    ["lElbow", "LeftForeArm"],
    ["lWrist", "LeftHand"],
    ["lToes", "LeftToeBase"],
    // Anatomical-Latin limbs, in both the side-SUFFIX (`humerus.L`) and
    // side-PREFIX (`l_humerus`) spellings these rigs ship in. The clavicle
    // (`scapula`) is already covered above. Radius and ulna are both forearm
    // joints and fibula is a lower-leg joint: whichever the rig actually skins
    // to is the one the retargeter binds (first-bone-wins), and either drives
    // the same limb segment.
    ["humerusL", "LeftArm"],
    ["lHumerus", "LeftArm"],
    ["ulnaL", "LeftForeArm"],
    ["lUlna", "LeftForeArm"],
    ["radiusL", "LeftForeArm"],
    ["lRadius", "LeftForeArm"],
    ["carpusL", "LeftHand"],
    ["lCarpus", "LeftHand"],
    ["femurL", "LeftUpLeg"],
    ["lFemur", "LeftUpLeg"],
    ["tibiaL", "LeftLeg"],
    ["lTibia", "LeftLeg"],
    ["fibulaL", "LeftLeg"],
    ["lFibula", "LeftLeg"],
    ["talusL", "LeftFoot"],
    ["lTalus", "LeftFoot"],
    ["tarsusL", "LeftFoot"],
    ["lTarsus", "LeftFoot"],
    ["calcaneusL", "LeftFoot"],
    ["lCalcaneus", "LeftFoot"],
    ["metatarsusL", "LeftToeBase"],
    ["lMetatarsus", "LeftToeBase"]
  ];
  for (const [vf, cf] of [
    ["Thumb", "Thumb"],
    ["Index", "Index"],
    ["Middle", "Middle"],
    ["Ring", "Ring"],
    ["Pinky", "Pinky"],
    ["Little", "Pinky"]
  ]) {
    const lf = vf.toLowerCase();
    for (let n = 1; n <= 3; n++) {
      SIDED.push([`l${vf}${n}`, `LeftHand${cf}${n}`]);
      SIDED.push([`f_${lf}.0${n}.L`, `LeftHand${cf}${n}`]);
      SIDED.push([`${lf}.0${n}.L`, `LeftHand${cf}${n}`]);
      const digit = ["Thumb", "Index", "Middle", "Ring", "Pinky"].indexOf(cf) + 1;
      if (digit > 0) SIDED.push([`finger${digit}-${n}.L`, `LeftHand${cf}${n}`]);
    }
    const phalanges = cf === "Thumb" ? ["Metacarpal", "Proximal", "Distal"] : ["Proximal", "Intermediate", "Distal"];
    phalanges.forEach((phalanx, i) => {
      SIDED.push([`left${vf}${phalanx}`, `LeftHand${cf}${i + 1}`]);
      SIDED.push([`${lf}_${phalanx.toLowerCase()}.L`, `LeftHand${cf}${i + 1}`]);
    });
  }
  for (let n = 1; n <= 3; n++) SIDED.push([`lMid${n}`, `LeftHandMiddle${n}`]);
  for (const [vf, cf] of [
    ["Thumb", "Thumb"],
    ["Index", "Index"],
    ["Middle", "Middle"],
    ["Ring", "Ring"],
    ["Pinky", "Pinky"],
    ["Little", "Pinky"]
  ]) {
    for (let n = 1; n <= 3; n++) {
      SIDED.push([`${vf}${n}L`, `LeftHand${cf}${n}`]);
      SIDED.push([`${vf}Finger${n}L`, `LeftHand${cf}${n}`]);
    }
    SIDED.push([`${vf}L`, `LeftHand${cf}1`]);
    SIDED.push([`${vf}FingerL`, `LeftHand${cf}1`]);
  }
  for (const [lv, lc] of SIDED) {
    put(lv, lc);
    const rc = lc.replace(/^Left/, "Right");
    let rv;
    if (/^left/.test(lv)) rv = lv.replace(/^left/, "right");
    else if (/^l[A-Z]/.test(lv)) rv = "r" + lv.slice(1);
    else if (/Left$/.test(lv)) rv = lv.replace(/Left$/, "Right");
    else if (/L$/.test(lv)) rv = lv.replace(/L$/, "R");
    else rv = lv;
    put(rv, rc);
  }
  return m;
})();
function canonicalizeBoneName(name) {
  if (typeof name !== "string" || !name) return null;
  const direct = _lookupBone(name);
  if (direct) return direct;
  const deduped = name.replace(/[._]\d+$/, "");
  if (deduped !== name) return _lookupBone(deduped);
  return null;
}
function _lookupBone(name) {
  let s = name;
  s = s.replace(/^(?:[A-Za-z][\w]*:)+/, "");
  s = s.replace(/^mixamorig\d*[_:]?/i, "");
  s = s.replace(/^Armature[_/]?/i, "");
  s = s.replace(/^(DEF|ORG|MCH)[-_]/i, "");
  s = s.replace(/^CH[_:]/i, "");
  s = s.replace(/^CC_Base_/i, "");
  s = s.replace(/^j_(?!bip|sec|adj)/i, "");
  s = s.replace(/^\d+\./, "").replace(/^!/, "").replace(/^joint[_:]/i, "");
  s = s.replace(/^Bip\d+[\s_]?/i, "");
  const key = s.replace(/[-_.\s]+/g, "").toLowerCase();
  return LOOKUP.get(key) ?? UNREAL_ALIASES.get(key) ?? EXTRA_ALIASES.get(key) ?? null;
}
var CLAVICLE_SPELLING = /shoulder|clavicle|collar|scapula/i;
var UPPER_ARM_SPELLING = /upper.?arm|humerus/i;
var COLLAR_ONLY_SPELLING = /clavicle|collar|scapula/i;
function resolveArmShoulderCollisions(plan, opts = {}) {
  const isAncestor = typeof opts.isAncestor === "function" ? opts.isAncestor : null;
  const count = /* @__PURE__ */ new Map();
  for (const p of plan) count.set(p.canonical, (count.get(p.canonical) || 0) + 1);
  const byHierarchy = (eligible, related, aboveRelated) => {
    if (eligible.length < 2 || !isAncestor) return eligible[0];
    const match = eligible.find(
      (e) => related.some((o) => o !== e && (aboveRelated ? isAncestor(e, o) : isAncestor(o, e)))
    );
    return match || eligible[0];
  };
  const move = (entry, from, to) => {
    entry.canonical = to;
    count.set(from, (count.get(from) || 1) - 1);
    count.set(to, (count.get(to) || 0) + 1);
  };
  let changed = 0;
  for (const side of ["Left", "Right"]) {
    const arm = `${side}Arm`;
    const shoulder = `${side}Shoulder`;
    if ((count.get(arm) || 0) < 2 || (count.get(shoulder) || 0) > 0) continue;
    const contenders = plan.filter((p) => p.canonical === arm);
    const upperArms = contenders.filter((p) => UPPER_ARM_SPELLING.test(p.raw));
    if (upperArms.length === 0) continue;
    const eligible = contenders.filter(
      (p) => CLAVICLE_SPELLING.test(p.raw) && !UPPER_ARM_SPELLING.test(p.raw)
    );
    const pick = byHierarchy(eligible, upperArms, true);
    if (!pick) continue;
    move(pick, arm, shoulder);
    changed++;
  }
  for (const side of ["Left", "Right"]) {
    const arm = `${side}Arm`;
    const shoulder = `${side}Shoulder`;
    if ((count.get(shoulder) || 0) < 2 || (count.get(arm) || 0) > 0) continue;
    const contenders = plan.filter((p) => p.canonical === shoulder);
    const collars = contenders.filter((p) => COLLAR_ONLY_SPELLING.test(p.raw));
    if (collars.length === 0) continue;
    const eligible = contenders.filter(
      (p) => /shoulder/i.test(p.raw) && !COLLAR_ONLY_SPELLING.test(p.raw)
    );
    const pick = byHierarchy(eligible, collars, false);
    if (!pick) continue;
    move(pick, shoulder, arm);
    changed++;
  }
  return changed;
}
function isAncestorNode(parentOf, aIndex, bIndex) {
  if (aIndex == null || bIndex == null || aIndex === bIndex) return false;
  const seen = /* @__PURE__ */ new Set([bIndex]);
  for (let cur = parentOf.get(bIndex); cur != null && !seen.has(cur); cur = parentOf.get(cur)) {
    if (cur === aIndex) return true;
    seen.add(cur);
  }
  return false;
}
function canonicalizeJointNodes(json) {
  if (!json || !Array.isArray(json.nodes) || !Array.isArray(json.skins)) {
    return { renamed: 0, samples: [] };
  }
  const jointIndices = /* @__PURE__ */ new Set();
  for (const skin of json.skins) {
    if (Array.isArray(skin.joints)) {
      for (const idx of skin.joints) jointIndices.add(idx);
    }
  }
  const plan = [];
  for (const idx of jointIndices) {
    const node = json.nodes[idx];
    if (!node || typeof node.name !== "string") continue;
    const canonical = canonicalizeBoneName(node.name);
    if (!canonical || canonical === node.name) continue;
    plan.push({ node, index: idx, raw: node.name, canonical });
  }
  const parentOf = /* @__PURE__ */ new Map();
  json.nodes.forEach((n, i) => {
    if (Array.isArray(n.children)) for (const c of n.children) parentOf.set(c, i);
  });
  resolveArmShoulderCollisions(plan, {
    isAncestor: (a, b) => isAncestorNode(parentOf, a.index, b.index)
  });
  const planned = new Set(plan.map((p) => p.node));
  const taken = /* @__PURE__ */ new Set();
  for (const idx of jointIndices) {
    const node = json.nodes[idx];
    if (node && typeof node.name === "string" && !planned.has(node)) taken.add(node.name);
  }
  let renamed = 0;
  const samples = [];
  for (const p of plan) {
    if (taken.has(p.canonical)) continue;
    taken.add(p.canonical);
    if (samples.length < 5) samples.push({ from: p.raw, to: p.canonical });
    p.node.name = p.canonical;
    renamed++;
  }
  return { renamed, samples };
}
function nodeLocalMatrix(node) {
  const t = new Vector3();
  const r = new Quaternion();
  const s = new Vector3(1, 1, 1);
  if (node.translation) t.fromArray(node.translation);
  if (node.rotation) r.fromArray(node.rotation);
  if (node.scale) s.fromArray(node.scale);
  return new Matrix4().compose(t, r, s);
}
function worldMatricesFor(json, indices, parentOf) {
  const local = json.nodes.map(nodeLocalMatrix);
  const cache = /* @__PURE__ */ new Map();
  const visiting = /* @__PURE__ */ new Set();
  const world = (idx) => {
    if (cache.has(idx)) return cache.get(idx);
    if (visiting.has(idx)) return local[idx].clone();
    visiting.add(idx);
    const p = parentOf.get(idx);
    const m = p == null ? local[idx].clone() : world(p).clone().multiply(local[idx]);
    visiting.delete(idx);
    cache.set(idx, m);
    return m;
  };
  const out = /* @__PURE__ */ new Map();
  for (const idx of indices) out.set(idx, world(idx));
  return out;
}
function canonicalizeArmatureOrientation(json) {
  if (!json || !Array.isArray(json.nodes) || !Array.isArray(json.skins)) {
    return { corrected: false };
  }
  const parentOf = /* @__PURE__ */ new Map();
  json.nodes.forEach((n, i) => {
    if (Array.isArray(n.children)) for (const c of n.children) parentOf.set(c, i);
  });
  const jointIdx = /* @__PURE__ */ new Set();
  for (const skin of json.skins) {
    if (Array.isArray(skin.joints)) for (const j of skin.joints) jointIdx.add(j);
  }
  if (jointIdx.size === 0) return { corrected: false };
  let hips = -1;
  for (const idx of jointIdx) {
    const n = json.nodes[idx];
    if (n && typeof n.name === "string" && canonicalizeBoneName(n.name) === "Hips") {
      hips = idx;
      break;
    }
  }
  if (hips < 0) return { corrected: false };
  const pIdx = parentOf.get(hips);
  if (pIdx == null) return { corrected: false };
  const parent = json.nodes[pIdx];
  const r = new Quaternion();
  if (parent.rotation) r.fromArray(parent.rotation);
  if (1 - Math.abs(r.w) < ORIENT_EPS) return { corrected: false };
  const s = parent.scale;
  if (s && (Math.abs(s[0] - s[1]) > ORIENT_EPS || Math.abs(s[1] - s[2]) > ORIENT_EPS)) {
    return { corrected: false };
  }
  const children = Array.isArray(parent.children) ? parent.children : [];
  if (children.length === 0) return { corrected: false };
  const checkSet = /* @__PURE__ */ new Set([...jointIdx, ...children]);
  const before = worldMatricesFor(json, checkSet, parentOf);
  const snapshot = children.map((c) => ({
    idx: c,
    translation: json.nodes[c].translation ? json.nodes[c].translation.slice() : null,
    rotation: json.nodes[c].rotation ? json.nodes[c].rotation.slice() : null
  }));
  const parentRotBefore = parent.rotation ? parent.rotation.slice() : null;
  for (const c of children) {
    const node = json.nodes[c];
    const t = new Vector3();
    if (node.translation) t.fromArray(node.translation);
    t.applyQuaternion(r);
    node.translation = [t.x, t.y, t.z];
    const cr = new Quaternion();
    if (node.rotation) cr.fromArray(node.rotation);
    cr.premultiply(r);
    node.rotation = [cr.x, cr.y, cr.z, cr.w];
  }
  parent.rotation = [0, 0, 0, 1];
  const after = worldMatricesFor(json, checkSet, parentOf);
  let ok = true;
  for (const idx of checkSet) {
    const a = before.get(idx).elements;
    const b = after.get(idx).elements;
    for (let k = 0; k < 16; k++) {
      if (Math.abs(a[k] - b[k]) > ORIENT_WORLD_EPS) {
        ok = false;
        break;
      }
    }
    if (!ok) break;
  }
  if (!ok) {
    for (const snap of snapshot) {
      const node = json.nodes[snap.idx];
      if (snap.translation) node.translation = snap.translation;
      else delete node.translation;
      if (snap.rotation) node.rotation = snap.rotation;
      else delete node.rotation;
    }
    if (parentRotBefore) parent.rotation = parentRotBefore;
    else delete parent.rotation;
    return { corrected: false };
  }
  const hipsQ = new Quaternion();
  if (json.nodes[hips].rotation) hipsQ.fromArray(json.nodes[hips].rotation);
  return { corrected: true, hipsIdentity: 1 - Math.abs(hipsQ.w) < ORIENT_EPS };
}
function canonicalizeGLBBones(arrayBuffer) {
  if (!(arrayBuffer instanceof ArrayBuffer)) {
    throw new TypeError("canonicalizeGLBBones: ArrayBuffer required");
  }
  if (arrayBuffer.byteLength < 20) {
    throw new Error("canonicalizeGLBBones: buffer too small to be a GLB");
  }
  const view = new DataView(arrayBuffer);
  if (view.getUint32(0, true) !== GLB_MAGIC) {
    throw new Error("canonicalizeGLBBones: not a GLB (bad magic number)");
  }
  if (view.getUint32(4, true) !== 2) {
    throw new Error("canonicalizeGLBBones: only GLB v2 is supported");
  }
  const c0Len = view.getUint32(12, true);
  const c0Type = view.getUint32(16, true);
  if (c0Type !== CHUNK_TYPE_JSON) {
    throw new Error("canonicalizeGLBBones: chunk 0 must be JSON");
  }
  const jsonBytes = new Uint8Array(arrayBuffer, 20, c0Len);
  let json;
  try {
    json = JSON.parse(new TextDecoder().decode(jsonBytes));
  } catch (err) {
    throw new Error("canonicalizeGLBBones: JSON chunk parse failed: " + err.message);
  }
  const { renamed, samples } = canonicalizeJointNodes(json);
  const orientation = canonicalizeArmatureOrientation(json);
  if (renamed === 0 && !orientation.corrected) {
    return { buffer: arrayBuffer, renamed: 0, samples: [], orientationCorrected: false };
  }
  const c1Offset = 20 + c0Len;
  let c1Len = 0, c1Type = 0, binBytes = null;
  if (c1Offset + 8 <= arrayBuffer.byteLength) {
    c1Len = view.getUint32(c1Offset, true);
    c1Type = view.getUint32(c1Offset + 4, true);
    binBytes = new Uint8Array(arrayBuffer, c1Offset + 8, c1Len);
  }
  let newJsonBytes = new TextEncoder().encode(JSON.stringify(json));
  const jsonPad = (4 - newJsonBytes.length % 4) % 4;
  if (jsonPad) {
    const padded = new Uint8Array(newJsonBytes.length + jsonPad);
    padded.set(newJsonBytes);
    for (let i = 0; i < jsonPad; i++) padded[newJsonBytes.length + i] = 32;
    newJsonBytes = padded;
  }
  const totalLen = 12 + 8 + newJsonBytes.length + (binBytes ? 8 + binBytes.length : 0);
  const out = new ArrayBuffer(totalLen);
  const outView = new DataView(out);
  const outU8 = new Uint8Array(out);
  outView.setUint32(0, GLB_MAGIC, true);
  outView.setUint32(4, 2, true);
  outView.setUint32(8, totalLen, true);
  outView.setUint32(12, newJsonBytes.length, true);
  outView.setUint32(16, CHUNK_TYPE_JSON, true);
  outU8.set(newJsonBytes, 20);
  if (binBytes) {
    const binOffset = 20 + newJsonBytes.length;
    outView.setUint32(binOffset, c1Len, true);
    outView.setUint32(binOffset + 4, c1Type, true);
    outU8.set(binBytes, binOffset + 8);
  }
  return { buffer: out, renamed, samples, orientationCorrected: orientation.corrected };
}

// ../../src/animation-retarget.js
import { AnimationClip, Box3, Quaternion as Quaternion2, Vector3 as Vector32 } from "three";

// ../../src/animation-canonical-rest.js
var CANONICAL_REST = Object.freeze({
  Head: [-0.11706881878098431, 9438845082943482e-24, 372654613409334e-22, 0.993123804804428],
  Hips: [0.0010741148761599644, 6537898515070053e-23, 7022659074474357e-26, 0.9999994231384476],
  LeftArm: [-0.003089573263103852, 0.08018148459223169, -0.025041829737156785, 0.9964608827384491],
  LeftFoot: [0.5523737034239063, 2518875693487157e-19, 0.0036538113016644914, 0.8335885543726441],
  LeftForeArm: [-26826767953574183e-23, 12816740072681333e-23, 0.012369379808617674, 0.9999234962951223],
  LeftHand: [-0.0012910117424963744, 0.002537815819541244, 0.010116448936771993, 0.9999447735952517],
  LeftHandIndex1: [0.028803290204768833, 0.011362422123976328, 0.055965724298468224, 0.9979524555518633],
  LeftHandIndex2: [-0.005195237841915152, 0.014315447122751807, 21581682553071412e-20, 0.9998840087229335],
  LeftHandIndex3: [-0.0013151015878908295, -0.016941197343945148, -0.006458334440614796, 0.9998347644768737],
  LeftHandMiddle1: [0.027532617383276645, -0.031607746403852485, 0.016931179482824785, 0.9989775976007271],
  LeftHandMiddle2: [-0.001267981765488788, 0.0010468621884392817, -0.004466175725402764, 0.9999886747239598],
  LeftHandMiddle3: [-0.0015803533040544947, 0.0018352886876119797, 0.0038040706030556736, 0.9999898315711762],
  LeftHandPinky1: [0.009345046842530319, -0.07377447953394943, -0.04207784173776388, 0.9963430390695086],
  LeftHandPinky2: [-0.0019002180814112143, 2770864318134059e-19, 4968185265866393e-19, 0.9999980327809131],
  LeftHandPinky3: [-0.0014157518824255099, -0.005595319155644295, -0.004606740515194791, 0.9999727326242506],
  LeftHandRing1: [0.018207541076892743, -0.03436008716699126, -0.036855349348364555, 0.9985637451229231],
  LeftHandRing2: [-6571286469542868e-19, 0.015477225242721515, 0.01003471962236163, 0.9998296495318929],
  LeftHandRing3: [0.001815607007583071, -0.003099595768579846, 0.0072046170631637615, 0.999967594260012],
  LeftHandThumb1: [0.1900227602341495, -0.058826116539083484, 0.3478783626049766, 0.9161940209594684],
  LeftHandThumb2: [0.01801363066187901, -0.020968588375318502, -0.22063576333056306, 0.9749644544258214],
  LeftHandThumb3: [-0.014498565873099356, 0.03466160908487025, 0.011599642488048602, 0.9992266072970502],
  LeftLeg: [-0.022987686472704008, -6608912898930547e-19, 7960712093047862e-20, 0.9997355266049326],
  LeftShoulder: [0.5181706903502131, 0.5134387363832796, -0.48299308003518876, 0.4843526489955184],
  LeftToeBase: [0.23652217735689587, 6644108661216591e-19, -0.002051152932444189, 0.9716236982227819],
  LeftUpLeg: [-4952055077835167e-20, 0.0032567616062252223, 0.9999946186652781, -39203576089550014e-20],
  Neck: [0.2225107795455935, -23352293238692193e-23, -19647139012020554e-24, 0.974930229804142],
  RightArm: [-0.003089570938390283, -0.08018164882954634, 0.025041835809991725, 0.9964608693782484],
  RightFoot: [0.5523737112340288, -25188760500252676e-20, -0.0036538107149212758, 0.8335885491999105],
  RightForeArm: [-2706717415294191e-22, 60675494372234465e-25, -0.012369382961452784, 0.9999234962561284],
  RightHand: [-0.0012910114923260908, -0.002537815924782367, -0.010116450486313036, 0.9999447735796301],
  RightHandIndex1: [0.028803284769926347, -0.01136242475457741, -0.05596571258489258, 0.9979524563349205],
  RightHandIndex2: [-0.005195229421057241, -0.014315442233259087, -21582506177354608e-20, 0.9998840088349297],
  RightHandIndex3: [-0.0013151045827228072, 0.016941319655899355, 0.006458327385746035, 0.9998347624459482],
  RightHandMiddle1: [0.027532662650122483, 0.03160778647114273, -0.01693120529165708, 0.9989775946478099],
  RightHandMiddle2: [-0.0012680273836480952, -0.0010469047250576836, 0.004466199390698661, 0.9999886745158897],
  RightHandMiddle3: [-0.0015803562094451136, -0.001835288773706009, -0.003804068167924464, 0.9999898315756854],
  RightHandPinky1: [0.009345030274151258, 0.07377446325595405, 0.04207784564870347, 0.9963430402658229],
  RightHandPinky2: [-0.0019002008217559384, -2769620503994165e-19, -4968229018870381e-19, 0.9999980328459891],
  RightHandPinky3: [-0.0014157497333212687, 0.0055951881780210025, 0.004606743315319843, 0.9999727333471813],
  RightHandRing1: [0.01820754441109714, 0.03436008722907556, 0.03685535429214536, 0.9985637448787448],
  RightHandRing2: [-6571335802527884e-19, -0.015477230302375519, -0.010034723368684675, 0.999829649412505],
  RightHandRing3: [0.0018156069598940002, 0.003099598646436032, -0.007204626788931167, 0.9999675941811231],
  RightHandThumb1: [0.19002276085311107, 0.05882618704962336, -0.3478783387552461, 0.9161940229953188],
  RightHandThumb2: [0.018013663274561078, 0.020968550782212388, 0.22063584956274163, 0.9749644350943654],
  RightHandThumb3: [-0.014498582331377526, -0.03466161961137741, -0.011599713293476643, 0.9992266058762234],
  RightLeg: [-0.022987685898989207, 6609402907891899e-19, -7960698692017099e-20, 0.9997355265857409],
  RightShoulder: [0.5181706006117709, -0.5134387953473804, 0.4829931766210871, 0.4843525890882128],
  RightToeBase: [0.23652222014073046, -6644109035597298e-19, 0.0020511526077556528, 0.9716236878087238],
  RightUpLeg: [49438593286575395e-21, 0.003256759408127677, 0.9999946186765544, 3920356017771739e-19],
  Spine: [-0.018332562694809717, -14544833124647894e-23, 26976261645093277e-26, 0.9998319444511763],
  Spine1: [-0.03815628571181615, 20010687323521798e-24, 534807122500512e-23, 0.9992717837809081],
  Spine2: [-0.056998031890724846, 13546308636882866e-23, -8861325315468363e-25, 0.9983742907149431]
});
var CANONICAL_REST_WORLD = Object.freeze({
  Head: [-0.005402766806732544, -15440620455356296e-23, 834223242528311e-24, 0.9999854049488961],
  Hips: [0.0010741148761599644, 6537898515070053e-23, 7022659074474357e-26, 0.9999994231384476],
  LeftArm: [0.4889318420664182, 0.5108287295941205, -0.5108290884863605, 0.48893078190839623],
  LeftFoot: [-7112359875637768e-20, 0.5349085331963127, 0.8448985998862468, -0.004383145577274271],
  LeftForeArm: [0.49521300584988104, 0.5047420653526237, -0.5047420379230214, 0.4952120815979495],
  LeftHand: [0.5009334720626235, 0.5016127781291206, -0.4977959877649929, 0.49964931327533474],
  LeftHandIndex1: [0.5480286232738694, 0.46388966319384123, -0.47756977349452234, 0.506357703809076],
  LeftHandIndex2: [0.5522711480640051, 0.4734474077351049, -0.46714980714345783, 0.5026083894226862],
  LeftHandIndex3: [0.5405471533897316, 0.46903549072233974, -0.4790521335412708, 0.5082553896043159],
  LeftHandMiddle1: [0.5069366564823887, 0.46312011824587895, -0.5184714785961669, 0.5096295836965867],
  LeftHandMiddle2: [0.5047591066222094, 0.46656986579426646, -0.5196237813564338, 0.5074661907650336],
  LeftHandMiddle3: [0.5066805225023663, 0.46639751835165716, -0.5160243364733309, 0.509379123507728],
  LeftHandPinky1: [0.4459394011607033, 0.4793433042957962, -0.5586434331057456, 0.5092009093395086],
  LeftHandPinky2: [0.4453638702749085, 0.48032344738196137, -0.5573549331147079, 0.5101920146245817],
  LeftHandPinky3: [0.4392981168466072, 0.4802964151504903, -0.5615019919023976, 0.5109286011992389],
  LeftHandRing1: [0.47371996160924823, 0.4931227775910745, -0.5418410288696247, 0.4886999364902444],
  LeftHandRing2: [0.48665266892609377, 0.49620490515614146, -0.5291888435031511, 0.48673303168821197],
  LeftHandRing3: [0.48945530931580683, 0.4902132045285772, -0.5280743092981476, 0.4911843263440915],
  LeftHandThumb1: [0.6991138207882592, 0.16132571576360077, -0.40704633814367386, 0.5652673372230799],
  LeftHandThumb2: [0.64766423234777, 0.2923511094450446, -0.5391393932239583, 0.4520957758831027],
  LeftHandThumb3: [0.6626872004096495, 0.3080994487427915, -0.5067905211225114, 0.45725679518685386],
  LeftLeg: [6202111480182034e-19, -0.020805293540463354, 0.9997832430476497, -47118992773536965e-20],
  LeftShoulder: [0.4605450937998788, 0.4559956590278878, -0.537559006794696, 0.5394409087622226],
  LeftToeBase: [-0.002764355526654178, 0.7195640056310856, 0.6944147145329627, -0.0028643286082629046],
  LeftUpLeg: [-4987623525000119e-20, 0.0021826506059020026, 0.9999975399461294, -39198262713485737e-20],
  Neck: [0.11170149382802401, -1628868597421303e-22, -18309282630496044e-24, 0.9937418056399563],
  RightArm: [0.48893190585215524, -0.510828798312402, 0.5108290283825504, 0.48893071200502375],
  RightFoot: [7112360405196955e-20, 0.5349085398019146, 0.844898595705669, 0.004383145306035045],
  RightForeArm: [0.4952130023257164, -0.5047420673116668, 0.5047420415306929, 0.49521208229448166],
  RightHand: [0.5009334695181179, -0.5016127792807403, 0.49779599067075153, 0.49964931459617906],
  RightHandIndex1: [0.5480286139806538, -0.4638896746971551, 0.47756977858103167, 0.5063577013132781],
  RightHandIndex2: [0.5522711446711094, -0.4734474082706649, 0.4671498147721218, 0.5026083883587248],
  RightHandIndex3: [0.5405470934413514, -0.4690354263302402, 0.47905220274050364, 0.5082554503330304],
  RightHandMiddle1: [0.5069366680435416, -0.4631200623546733, 0.5184715098400317, 0.5096295939664325],
  RightHandMiddle2: [0.5047591061928465, -0.46656986723577604, 0.5196237819671778, 0.5074661920185282],
  RightHandMiddle3: [0.5066805195140975, -0.4663975225827095, 0.5160243369168757, 0.5093791249234989],
  RightHandPinky1: [0.44593939683725603, -0.4793433241517071, 0.5586434219640141, 0.5092009094272859],
  RightHandPinky2: [0.4453638073881448, -0.4803233923217868, 0.5573549834847424, 0.5101920690951159],
  RightHandPinky3: [0.4392981269307416, -0.4802964267888911, 0.5615019867791732, 0.5109285899785551],
  RightHandRing1: [0.473719957983008, -0.49312277921221886, 0.541841035805614, 0.4886999335659278],
  RightHandRing2: [0.48665266754747677, -0.4962049100848307, 0.5291888437972645, 0.4867330306200585],
  RightHandRing3: [0.4894553112113337, -0.49021320332208906, 0.5280743062058759, 0.4911843318554558],
  RightHandThumb1: [0.6991137732035737, -0.16132569450370315, 0.40704638897707807, 0.5652673641991798],
  RightHandThumb2: [0.6476641963757893, -0.2923511418798965, 0.5391394672107036, 0.45209571648691005],
  RightHandThumb3: [0.6626871862817486, -0.3080994479425299, 0.5067905521115058, 0.4572567801626834],
  RightLeg: [-6202113471683521e-19, -0.020805295090456907, 0.9997832430151556, 47119017271936915e-20],
  RightShoulder: [0.46054507521936333, -0.45599564278848104, 0.5375590232993736, 0.5394409245169026],
  RightToeBase: [0.0027643555231566678, 0.7195640416379069, 0.6944146772223956, 0.0028643286020973192],
  RightUpLeg: [49925034443819034e-21, 0.002182648459062353, 0.9999975399486299, 39198198974856475e-20],
  Spine: [-0.017258617754264162, -800818266828763e-22, 13823133775055108e-25, 0.9998510589648868],
  Spine1: [-0.055396652424023016, -5997624651397324e-23, 332759956947242e-23, 0.9984644264570511],
  Spine2: [-0.11221710079276989, 7513757486198981e-23, -8485311473269302e-24, 0.9936837134066652]
});
var CANONICAL_REST_POSITION = Object.freeze({
  Head: [0, 1.644459, -0.02811],
  Hips: [0, 0.984089, 5022e-6],
  LeftArm: [0.204124, 1.50826, -0.034115],
  LeftFoot: [0.095793, 0.088025, -0.010018],
  LeftForeArm: [0.458234, 1.508259, -0.045252],
  LeftHand: [0.725837, 1.508259, -0.050352],
  LeftHandIndex1: [0.815413, 1.499602, -0.024435],
  LeftHandIndex2: [0.847794, 1.497747, -0.020782],
  LeftHandIndex3: [0.872293, 1.496601, -0.017998],
  LeftHandMiddle1: [0.820067, 1.500491, -0.048839],
  LeftHandMiddle2: [0.854745, 1.498698, -0.047572],
  LeftHandMiddle3: [0.881683, 1.49736, -0.046832],
  LeftHandPinky1: [0.810112, 1.495569, -0.08743],
  LeftHandPinky2: [0.8359, 1.495002, -0.089537],
  LeftHandPinky3: [0.856018, 1.494638, -0.091171],
  LeftHandRing1: [0.81666, 1.499944, -0.069059],
  LeftHandRing2: [0.850313, 1.498728, -0.071468],
  LeftHandRing3: [0.87354, 1.497943, -0.072665],
  LeftHandThumb1: [0.761831, 1.487283, -0.024616],
  LeftHandThumb2: [0.783931, 1.477329, -3377e-6],
  LeftHandThumb3: [0.806009, 1.466616, 3515e-6],
  LeftLeg: [0.09541, 0.505427, 7362e-6],
  LeftShoulder: [0.056656, 1.508576, -0.035092],
  LeftToeBase: [0.096919, 0.022281, 0.128921],
  LeftUpLeg: [0.095072, 0.936186, 5482e-6],
  Neck: [0, 1.516499, -0.057245],
  RightArm: [-0.204124, 1.50826, -0.034115],
  RightFoot: [-0.095793, 0.088025, -0.010018],
  RightForeArm: [-0.458234, 1.508259, -0.045252],
  RightHand: [-0.725837, 1.508259, -0.050352],
  RightHandIndex1: [-0.815413, 1.499602, -0.024435],
  RightHandIndex2: [-0.847794, 1.497747, -0.020783],
  RightHandIndex3: [-0.872293, 1.496601, -0.017998],
  RightHandMiddle1: [-0.820067, 1.500491, -0.048839],
  RightHandMiddle2: [-0.854745, 1.498698, -0.047572],
  RightHandMiddle3: [-0.881683, 1.49736, -0.046832],
  RightHandPinky1: [-0.810112, 1.495569, -0.08743],
  RightHandPinky2: [-0.8359, 1.495002, -0.089537],
  RightHandPinky3: [-0.856018, 1.494638, -0.091171],
  RightHandRing1: [-0.81666, 1.499944, -0.069059],
  RightHandRing2: [-0.850313, 1.498729, -0.071468],
  RightHandRing3: [-0.87354, 1.497943, -0.072665],
  RightHandThumb1: [-0.761831, 1.487283, -0.024615],
  RightHandThumb2: [-0.783931, 1.477329, -3377e-6],
  RightHandThumb3: [-0.806009, 1.466616, 3515e-6],
  RightLeg: [-0.09541, 0.505427, 7362e-6],
  RightShoulder: [-0.056656, 1.508576, -0.035092],
  RightToeBase: [-0.096919, 0.022281, 0.128921],
  RightUpLeg: [-0.095072, 0.936186, 5482e-6],
  Spine: [0, 1.077013, 5222e-6],
  Spine1: [0, 1.184494, 151e-5],
  Spine2: [0, 1.330917, -0.014788]
});
var CANONICAL_PARENT = Object.freeze({
  Head: "Neck",
  Hips: null,
  LeftArm: "LeftShoulder",
  LeftFoot: "LeftLeg",
  LeftForeArm: "LeftArm",
  LeftHand: "LeftForeArm",
  LeftHandIndex1: "LeftHand",
  LeftHandIndex2: "LeftHandIndex1",
  LeftHandIndex3: "LeftHandIndex2",
  LeftHandMiddle1: "LeftHand",
  LeftHandMiddle2: "LeftHandMiddle1",
  LeftHandMiddle3: "LeftHandMiddle2",
  LeftHandPinky1: "LeftHand",
  LeftHandPinky2: "LeftHandPinky1",
  LeftHandPinky3: "LeftHandPinky2",
  LeftHandRing1: "LeftHand",
  LeftHandRing2: "LeftHandRing1",
  LeftHandRing3: "LeftHandRing2",
  LeftHandThumb1: "LeftHand",
  LeftHandThumb2: "LeftHandThumb1",
  LeftHandThumb3: "LeftHandThumb2",
  LeftLeg: "LeftUpLeg",
  LeftShoulder: "Spine2",
  LeftToeBase: "LeftFoot",
  LeftUpLeg: "Hips",
  Neck: "Spine2",
  RightArm: "RightShoulder",
  RightFoot: "RightLeg",
  RightForeArm: "RightArm",
  RightHand: "RightForeArm",
  RightHandIndex1: "RightHand",
  RightHandIndex2: "RightHandIndex1",
  RightHandIndex3: "RightHandIndex2",
  RightHandMiddle1: "RightHand",
  RightHandMiddle2: "RightHandMiddle1",
  RightHandMiddle3: "RightHandMiddle2",
  RightHandPinky1: "RightHand",
  RightHandPinky2: "RightHandPinky1",
  RightHandPinky3: "RightHandPinky2",
  RightHandRing1: "RightHand",
  RightHandRing2: "RightHandRing1",
  RightHandRing3: "RightHandRing2",
  RightHandThumb1: "RightHand",
  RightHandThumb2: "RightHandThumb1",
  RightHandThumb3: "RightHandThumb2",
  RightLeg: "RightUpLeg",
  RightShoulder: "Spine2",
  RightToeBase: "RightFoot",
  RightUpLeg: "Hips",
  Spine: "Hips",
  Spine1: "Spine",
  Spine2: "Spine1"
});

// ../../src/animation-retarget.js
var MIN_COVERAGE = 0.5;
var SOURCE_REST = new Map(
  Object.entries(CANONICAL_REST).map(([bone, q]) => [bone, new Quaternion2(q[0], q[1], q[2], q[3])])
);
var SOURCE_WORLD_REST = new Map(
  Object.entries(CANONICAL_REST_WORLD).map(([bone, q]) => [
    bone,
    new Quaternion2(q[0], q[1], q[2], q[3])
  ])
);
var BIND_EPSILON = 1e-6;
var MORPH_PROPERTY = "morphTargetInfluences[";
var _v = new Vector32();
var _q = new Quaternion2();
function isAncestorObject(a, b) {
  for (let n = b?.parent; n; n = n.parent) if (n === a) return true;
  return false;
}
function canonicalBoneEntries(root) {
  const entries = [];
  const seen = /* @__PURE__ */ new Set();
  const consider = (node) => {
    if (!node?.name || seen.has(node)) return;
    seen.add(node);
    const canonical = canonicalizeBoneName(node.name);
    if (canonical) entries.push({ node, raw: node.name, canonical });
  };
  const skinned = [];
  root.traverse((node) => {
    if (node.isSkinnedMesh) skinned.push(node);
    if (node.isBone) consider(node);
  });
  for (const sm of skinned) {
    for (const bone of sm.skeleton?.bones || []) consider(bone);
  }
  resolveArmShoulderCollisions(entries, {
    isAncestor: (a, b) => isAncestorObject(a.node, b.node)
  });
  const claimed = /* @__PURE__ */ new Set();
  return entries.filter((e) => {
    if (claimed.has(e.canonical)) return false;
    claimed.add(e.canonical);
    return true;
  });
}
function canonicalNodeMapFromObject(root) {
  const map = /* @__PURE__ */ new Map();
  for (const { canonical, node } of canonicalBoneEntries(root)) map.set(canonical, node.name);
  return map;
}
function canonicalNodeMapFromRig(rig) {
  const map = /* @__PURE__ */ new Map();
  for (const { key, node } of rig.getBones?.() || []) {
    if (node?.name) map.set(key, node.name);
  }
  return map;
}
function canonicalRestMapFromObject(root) {
  const map = /* @__PURE__ */ new Map();
  for (const { canonical, node } of canonicalBoneEntries(root)) {
    map.set(canonical, node.quaternion.clone());
  }
  return map;
}
function canonicalRestMapFromRig(rig) {
  const map = /* @__PURE__ */ new Map();
  for (const { key, node } of rig.getBones?.() || []) {
    if (node && !map.has(key)) map.set(key, node.quaternion.clone());
  }
  return map;
}
function worldRestQuat(node, stopAt) {
  const q = node.quaternion.clone();
  for (let n = node.parent; n && n !== stopAt; n = n.parent) q.premultiply(n.quaternion);
  return q;
}
function canonicalWorldRestMapFromObject(root) {
  const map = /* @__PURE__ */ new Map();
  for (const { canonical, node } of canonicalBoneEntries(root)) {
    map.set(canonical, worldRestQuat(node, root));
  }
  return map;
}
function canonicalWorldRestMapFromRig(rig) {
  const map = /* @__PURE__ */ new Map();
  for (const { key, node } of rig.getBones?.() || []) {
    if (node && !map.has(key)) map.set(key, worldRestQuat(node, null));
  }
  return map;
}
function morphTargetMapFromObject(root) {
  const map = /* @__PURE__ */ new Map();
  root.traverse((node) => {
    if (!node?.morphTargetDictionary || !node.name) return;
    for (const shape of Object.keys(node.morphTargetDictionary)) {
      if (!map.has(shape)) map.set(shape, []);
      const meshes = map.get(shape);
      if (!meshes.includes(node.name)) meshes.push(node.name);
    }
  });
  return map;
}
function bindCorrections(targetRest, targetWorldRest) {
  const out = /* @__PURE__ */ new Map();
  if (!(targetRest instanceof Map)) return out;
  const haveWorld = targetWorldRest instanceof Map;
  for (const [canonical, Rs] of SOURCE_REST) {
    const Rt = targetRest.get(canonical);
    if (!Rt) continue;
    const WS = SOURCE_WORLD_REST.get(canonical);
    const WT = haveWorld ? targetWorldRest.get(canonical) : null;
    let L;
    let R = null;
    if (WS && WT) {
      L = Rt.clone().multiply(WT.clone().invert()).multiply(WS).multiply(Rs.clone().invert());
      R = WS.clone().invert().multiply(WT);
      if (1 - Math.abs(R.w) < BIND_EPSILON) R = null;
    } else {
      L = Rt.clone().multiply(Rs.clone().invert());
    }
    const identityL = 1 - Math.abs(L.w) < BIND_EPSILON;
    if (identityL && !R) continue;
    out.set(canonical, { L: identityL ? null : L, R });
  }
  return out;
}
function correctQuaternionTrack(values, { L, R }) {
  for (let i = 0; i < values.length; i += 4) {
    _q.set(values[i], values[i + 1], values[i + 2], values[i + 3]);
    if (L) _q.premultiply(L);
    if (R) _q.multiply(R);
    values[i] = _q.x;
    values[i + 1] = _q.y;
    values[i + 2] = _q.z;
    values[i + 3] = _q.w;
  }
}
function rotateVectorTrack(values, c) {
  for (let i = 0; i < values.length; i += 3) {
    _v.set(values[i], values[i + 1], values[i + 2]).applyQuaternion(c);
    values[i] = _v.x;
    values[i + 1] = _v.y;
    values[i + 2] = _v.z;
  }
}
function hipPositionCorrection(hipsParentWorldQuat2, corrections) {
  if (hipsParentWorldQuat2) {
    const q = hipsParentWorldQuat2.isQuaternion ? hipsParentWorldQuat2.clone() : new Quaternion2(
      hipsParentWorldQuat2[0],
      hipsParentWorldQuat2[1],
      hipsParentWorldQuat2[2],
      hipsParentWorldQuat2[3]
    );
    q.invert();
    return 1 - Math.abs(q.w) < BIND_EPSILON ? null : q;
  }
  return corrections.get("Hips")?.L || null;
}
function hipsParentWorldQuat(root) {
  let hips = null;
  root.traverse((n) => {
    if (!hips && n.isBone && n.name && canonicalizeBoneName(n.name) === "Hips") hips = n;
  });
  if (!hips) {
    root.traverse((n) => {
      if (!hips && n.name && canonicalizeBoneName(n.name) === "Hips") hips = n;
    });
  }
  if (!hips || !hips.parent) return null;
  const q = new Quaternion2();
  for (let n = hips.parent; n && n !== root; n = n.parent) q.premultiply(n.quaternion);
  return q;
}
function hipRestHeight(rig) {
  const hips = rig.getNode?.("Hips");
  if (!hips) return 0;
  hips.updateWorldMatrix(true, false);
  hips.getWorldPosition(_v);
  return _v.y;
}
function safeFloorY(root) {
  try {
    return new Box3().setFromObject(root).min.y;
  } catch {
    return NaN;
  }
}
var _hipScalePos = new Vector32();
var _hipScaleQuat = new Quaternion2();
var _hipScaleScale = new Vector32();
function hipRestLocalHeight(root) {
  let hips = null;
  root.traverse((n) => {
    if (!hips && n.isBone && n.name && canonicalizeBoneName(n.name) === "Hips") hips = n;
  });
  if (!hips) {
    root.traverse((n) => {
      if (!hips && n.name && canonicalizeBoneName(n.name) === "Hips") hips = n;
    });
  }
  if (!hips || !hips.parent) return 0;
  hips.updateWorldMatrix(true, false);
  hips.getWorldPosition(_hipScalePos);
  let worldY = _hipScalePos.y;
  const floorY = safeFloorY(root);
  if (Number.isFinite(floorY)) worldY -= floorY;
  hips.parent.matrixWorld.decompose(_hipScalePos, _hipScaleQuat, _hipScaleScale);
  const parentScale = (_hipScaleScale.x + _hipScaleScale.y + _hipScaleScale.z) / 3;
  if (!(parentScale > 1e-6) || !(worldY > 0) || !Number.isFinite(worldY)) return 0;
  return worldY / parentScale;
}
function hipGroundLocalY(root) {
  let hips = null;
  root.traverse((n) => {
    if (!hips && n.isBone && n.name && canonicalizeBoneName(n.name) === "Hips") hips = n;
  });
  if (!hips) {
    root.traverse((n) => {
      if (!hips && n.name && canonicalizeBoneName(n.name) === "Hips") hips = n;
    });
  }
  if (!hips || !hips.parent) return 0;
  hips.updateWorldMatrix(true, false);
  const floorY = safeFloorY(root);
  if (!Number.isFinite(floorY)) return 0;
  hips.getWorldPosition(_hipScalePos);
  const local = hips.parent.worldToLocal(new Vector32(_hipScalePos.x, floorY, _hipScalePos.z));
  return Number.isFinite(local.y) ? local.y : 0;
}
function clipHipBaselineY(clip) {
  const track = clip.tracks.find(
    (t) => t.name.endsWith(".position") && canonicalizeBoneName(t.name.split(".")[0]) === "Hips"
  );
  if (!track || track.values.length < 3) return 0;
  return track.values[1];
}
function retargetClip(clip, canonicalToNode, opts = {}) {
  const hipScale = Number.isFinite(opts.hipScale) && opts.hipScale > 0 ? opts.hipScale : 1;
  const hipOffsetY = Number.isFinite(opts.hipOffsetY) ? opts.hipOffsetY : 0;
  const minCoverage = opts.minCoverage ?? MIN_COVERAGE;
  const corrections = bindCorrections(opts.targetRest, opts.targetWorldRest);
  const hipsPosCorrection = hipPositionCorrection(opts.hipsParentWorldQuat, corrections);
  const dropped = [];
  const tracks = [];
  const faceTracks = [];
  let total = 0;
  for (const track of clip.tracks) {
    const dot = track.name.indexOf(".");
    if (dot === -1) continue;
    const boneRaw = track.name.slice(0, dot);
    const property = track.name.slice(dot + 1);
    if (property.startsWith(MORPH_PROPERTY)) {
      const shape = property.slice(MORPH_PROPERTY.length, -1);
      const meshes = opts.morphTargets instanceof Map ? opts.morphTargets.get(shape) : null;
      if (!meshes?.length) {
        dropped.push(shape);
        continue;
      }
      for (const meshName of meshes) {
        const next2 = track.clone();
        next2.name = `${meshName}.${property}`;
        faceTracks.push(next2);
      }
      continue;
    }
    const canonical = canonicalizeBoneName(boneRaw) || boneRaw;
    const retargetable = property === "quaternion" || property === "position" && canonical === "Hips";
    if (!retargetable) continue;
    total++;
    const nodeName = canonicalToNode.get(canonical);
    if (!nodeName) {
      dropped.push(canonical);
      continue;
    }
    const next = track.clone();
    next.name = `${nodeName}.${property}`;
    if (property === "quaternion") {
      const correction = corrections.get(canonical);
      if (correction) correctQuaternionTrack(next.values, correction);
    } else {
      if (hipsPosCorrection) rotateVectorTrack(next.values, hipsPosCorrection);
      if (hipScale !== 1) {
        for (let i = 0; i < next.values.length; i++) next.values[i] *= hipScale;
      }
      if (hipOffsetY !== 0) {
        for (let i = 1; i < next.values.length; i += 3) next.values[i] += hipOffsetY;
      }
    }
    tracks.push(next);
  }
  const matched = tracks.length;
  const coverage = total > 0 ? matched / total : 0;
  if (coverage < minCoverage) {
    return { clip: null, matched, total, coverage, dropped, hipScale, face: 0 };
  }
  const out = clip.clone();
  out.tracks = [...tracks, ...faceTracks];
  return { clip: out, matched, total, coverage, dropped, hipScale, face: faceTracks.length };
}
function retargetClipToRig(clip, rig, opts = {}) {
  rig.resetPose?.();
  const map = canonicalNodeMapFromRig(rig);
  const targetRest = canonicalRestMapFromRig(rig);
  const targetWorldRest = canonicalWorldRestMapFromRig(rig);
  let hipScale = 1;
  if (opts.scaleHips !== false) {
    const targetY = hipRestHeight(rig);
    const sourceY = clipHipBaselineY(clip);
    if (targetY > 0.05 && sourceY > 0.05) {
      hipScale = Math.min(5, Math.max(0.2, targetY / sourceY));
    }
  }
  let hipsParent = null;
  const hipsNode = rig.getNode?.("Hips");
  if (hipsNode?.parent) {
    hipsParent = new Quaternion2();
    for (let n = hipsNode.parent; n; n = n.parent) hipsParent.premultiply(n.quaternion);
  }
  return retargetClip(clip, map, {
    hipScale,
    minCoverage: opts.minCoverage,
    targetRest,
    targetWorldRest,
    hipsParentWorldQuat: hipsParent,
    morphTargets: opts.morphTargets ?? (rig.root ? morphTargetMapFromObject(rig.root) : void 0)
  });
}
function retargetClipToObject(clip, root, opts = {}) {
  const map = canonicalNodeMapFromObject(root);
  const targetRest = opts.targetRest || canonicalRestMapFromObject(root);
  const targetWorldRest = opts.targetWorldRest || canonicalWorldRestMapFromObject(root);
  const hipsParentWorld = opts.hipsParentWorldQuat || hipsParentWorldQuat(root);
  return retargetClip(clip, map, {
    ...opts,
    targetRest,
    targetWorldRest,
    hipsParentWorldQuat: hipsParentWorld,
    morphTargets: opts.morphTargets ?? morphTargetMapFromObject(root)
  });
}
function scaleClipSpeed(clip, factor) {
  if (!(factor > 0) || factor === 1) return clip;
  const out = clip.clone();
  const inv = 1 / factor;
  for (const track of out.tracks) {
    for (let i = 0; i < track.times.length; i++) track.times[i] *= inv;
  }
  out.duration = clip.duration * inv;
  return out;
}
function parseClipJSON(json, name) {
  const clip = AnimationClip.parse(json);
  if (name) clip.name = name;
  return clip;
}

// ../../src/animation-manager.js
import {
  AdditiveAnimationBlendMode,
  AnimationClip as AnimationClip2,
  AnimationMixer,
  AnimationUtils,
  LoopRepeat,
  LoopOnce,
  Quaternion as Quaternion4,
  Vector3 as Vector34
} from "three";

// ../../src/animation-arm-relax.js
import { Quaternion as Quaternion3, Vector3 as Vector33 } from "three";
var LATERAL_DOMINANCE = 0.6;
var VERTICAL_CAP = 0.38;
var ALREADY_RELAXED_Y = -0.4;
var _boneWorld = new Vector33();
var _childWorld = new Vector33();
var _childDir = new Vector33();
var _target = new Vector33();
var _worldDelta = new Quaternion3();
var _boneWorldQ = new Quaternion3();
var _parentWorldQ = new Quaternion3();
function firstChildBone(bone) {
  for (const c of bone.children) {
    if (c.isBone) return c;
  }
  return null;
}
function relaxUndrivenArms(model, canonicalToNode) {
  if (!model) return 0;
  if (canonicalToNode && (canonicalToNode.has("LeftArm") || canonicalToNode.has("RightArm"))) {
    return 0;
  }
  model.updateMatrixWorld(true);
  const bones = [];
  let minY = Infinity, maxY = -Infinity;
  model.traverse((node) => {
    if (!node.isBone) return;
    const child = firstChildBone(node);
    if (!child) return;
    node.getWorldPosition(_boneWorld);
    child.getWorldPosition(_childWorld);
    const dir = _childWorld.clone().sub(_boneWorld);
    const len = dir.length();
    if (len < 1e-5) return;
    dir.multiplyScalar(1 / len);
    const y = _boneWorld.y;
    if (y < minY) minY = y;
    if (y > maxY) maxY = y;
    bones.push({ bone: node, worldY: y, dir });
  });
  if (bones.length === 0 || !Number.isFinite(minY) || maxY <= minY) return 0;
  const midY = (minY + maxY) / 2;
  let bestPos = null;
  let bestNeg = null;
  for (const cand of bones) {
    const { dir, worldY } = cand;
    if (worldY < midY) continue;
    if (Math.abs(dir.y) > VERTICAL_CAP) continue;
    if (dir.y < ALREADY_RELAXED_Y) continue;
    if (Math.abs(dir.x) < LATERAL_DOMINANCE) continue;
    if (dir.x > 0) {
      if (!bestPos || dir.x > bestPos.dir.x) bestPos = cand;
    } else {
      if (!bestNeg || dir.x < bestNeg.dir.x) bestNeg = cand;
    }
  }
  let relaxed = 0;
  for (const cand of [bestPos, bestNeg]) {
    if (cand && relaxArm(cand.bone, cand.dir)) relaxed++;
  }
  return relaxed;
}
function relaxArm(bone, childDir) {
  const side = childDir.x >= 0 ? 1 : -1;
  _target.set(side * 0.14, -1, 0.1).normalize();
  _childDir.copy(childDir);
  _worldDelta.setFromUnitVectors(_childDir, _target);
  bone.getWorldQuaternion(_boneWorldQ);
  if (bone.parent) bone.parent.getWorldQuaternion(_parentWorldQ);
  else _parentWorldQ.identity();
  const desiredWorld = _worldDelta.multiply(_boneWorldQ);
  const localQ = _parentWorldQ.invert().multiply(desiredWorld);
  bone.quaternion.copy(localQ);
  bone.updateMatrixWorld(true);
  return true;
}

// ../../src/shared/log.js
function debugFlagEnabled() {
  if (typeof window === "undefined") return false;
  try {
    const params = new URLSearchParams(window.location.search);
    if (params.has("debug") && params.get("debug") !== "0") return true;
    if (window.localStorage?.getItem("tws:debug") === "1") return true;
  } catch {
  }
  return false;
}
var IS_DEV = Boolean(import.meta.env?.DEV);
var VERBOSE = IS_DEV || debugFlagEnabled();
var noop = () => {
};
var bind = (method) => typeof console !== "undefined" && typeof console[method] === "function" ? console[method].bind(console) : noop;
var log = {
  error: bind("error"),
  warn: VERBOSE ? bind("warn") : noop,
  info: VERBOSE ? bind("info") : noop,
  debug: VERBOSE ? bind("debug") : noop,
  log: VERBOSE ? bind("log") : noop
};

// ../../src/animation-manager.js
var CATASTROPHE_TILT_DEG = 45;
var _worldUp = new Vector34();
var _wq = new Quaternion4();
var _UP = new Vector34(0, 1, 0);
function measureHipsTiltDeg(retargetedClip, model, canonicalToNode) {
  if (!retargetedClip || !model || !canonicalToNode) return null;
  const hipsName = canonicalToNode.get("Hips");
  if (!hipsName) return null;
  const hips = model.getObjectByName(hipsName);
  if (!hips) return null;
  const qTrack = retargetedClip.tracks.find((t) => t.name === `${hipsName}.quaternion`);
  if (!qTrack || qTrack.values.length < 4) return null;
  const v = qTrack.values;
  const restX = hips.quaternion.x;
  const restY = hips.quaternion.y;
  const restZ = hips.quaternion.z;
  const restW = hips.quaternion.w;
  hips.quaternion.set(v[0], v[1], v[2], v[3]);
  hips.updateWorldMatrix(true, false);
  hips.getWorldQuaternion(_wq);
  _worldUp.copy(_UP).applyQuaternion(_wq);
  hips.quaternion.set(restX, restY, restZ, restW);
  hips.updateWorldMatrix(true, false);
  const dot = Math.max(-1, Math.min(1, _worldUp.dot(_UP)));
  return Math.acos(dot) * 180 / Math.PI;
}
var MIN_CANONICAL_BONES = 8;
var LOWER_BODY_CANONICAL = Object.freeze([
  "Hips",
  "LeftUpLeg",
  "LeftLeg",
  "LeftFoot",
  "LeftToeBase",
  "RightUpLeg",
  "RightLeg",
  "RightFoot",
  "RightToeBase"
]);
var DEFAULT_CROSSFADE = 0.35;
var _clipCache = /* @__PURE__ */ new Map();
var AnimationManager = class _AnimationManager {
  constructor() {
    this.model = null;
    this.mixer = null;
    this.clips = /* @__PURE__ */ new Map();
    this.actions = /* @__PURE__ */ new Map();
    this.currentName = null;
    this.currentAction = null;
    this.onChange = null;
    this._animationDefs = [];
    this._failed = /* @__PURE__ */ new Set();
    this._canonicalToNode = null;
    this._canonicalClipsSupported = false;
    this._avatarContext = {};
    this._fallenReported = /* @__PURE__ */ new Set();
    this._fallen = /* @__PURE__ */ new Set();
    this._latestCrossfadeTarget = null;
    this.overlayAction = null;
    this.overlayName = null;
    this._overlayClips = /* @__PURE__ */ new Map();
    this._overlayFinishHandler = null;
    this._settleFinishHandler = null;
  }
  /**
   * Tell the manager which avatar is attached so the fallen-pose guard can
   * report actionable context. Both fields are optional; url alone is enough.
   * @param {{ avatarId?: string, avatarUrl?: string }} ctx
   */
  setAvatarContext(ctx) {
    this._avatarContext = ctx && typeof ctx === "object" ? { ...ctx } : {};
  }
  // ── Model binding ──────────────────────────────────────────────────────────
  /**
   * Attach to a loaded model. Call this every time a new model is loaded.
   * Re-creates actions for any clips that are already in memory.
   * @param {THREE.Object3D} model
   * @param {{ avatarId?: string, avatarUrl?: string }} [context] avatar id/url for the fallen-pose guard
   */
  attach(model, context) {
    this.detach();
    if (context) this.setAvatarContext(context);
    this.model = model;
    this.mixer = new AnimationMixer(model);
    this.actions.clear();
    this.currentAction = null;
    this.currentName = null;
    this._fallen.clear();
    this._canonicalToNode = canonicalNodeMapFromObject(model);
    this._canonicalRest = canonicalRestMapFromObject(model);
    this._canonicalWorldRest = canonicalWorldRestMapFromObject(model);
    this._hipsParentWorldQuat = hipsParentWorldQuat(model);
    this._morphTargets = morphTargetMapFromObject(model);
    this._hipTargetLocalY = hipRestLocalHeight(model);
    this._hipGroundLocalY = hipGroundLocalY(model);
    this._canonicalClipsSupported = _modelSupportsCanonicalClips(model);
    for (const [name, clip] of this.clips) {
      const bound = this._retarget(clip);
      if (!bound) continue;
      const action = this.mixer.clipAction(bound);
      action.enabled = true;
      this.actions.set(name, action);
    }
  }
  /**
   * Re-measure the height-dependent retarget inputs and rebuild every bound
   * action. Call after a skeleton-space proportion edit (leg length, stature,
   * foot size: see src/avatar-proportions.js) has moved the rig's hips.
   *
   * A clip's hip translation is authored around one hip height and rescaled
   * onto the rig's actual height at attach time. Lengthen the legs and that
   * factor is stale: the root travels the old distance while longer legs cover
   * more ground per stride, and the avatar foot-slides. Re-measuring and
   * re-retargeting fixes the stride without re-attaching, which matters because
   * attach() also re-captures the rest frames: and those must be read in the
   * bind pose, which a mid-animation rig is not in.
   *
   * Safe precisely because proportions never touch bone *rotations*: the
   * captured local/world rest rotations, the canonical→node map and the morph
   * map all stay valid. The caller must have the rig at rest when it calls
   * (applyProportionsToRoot leaves it there).
   *
   * @returns {boolean} true when the rig was re-measured
   */
  remeasureRigProportions() {
    if (!this.model || !this.mixer) return false;
    const resume = this.currentName;
    this._hipTargetLocalY = hipRestLocalHeight(this.model);
    this._hipGroundLocalY = hipGroundLocalY(this.model);
    this.mixer.stopAllAction();
    for (const action of this.actions.values()) {
      const clip = action.getClip?.();
      if (clip) this.mixer.uncacheClip(clip);
    }
    this.actions.clear();
    this.currentAction = null;
    this.currentName = null;
    this._latestCrossfadeTarget = null;
    this.overlayAction = null;
    this.overlayName = null;
    this._overlayClips.clear();
    this._detachOverlayFinish?.();
    for (const [name, clip] of this.clips) {
      const bound = this._retarget(clip);
      if (!bound) continue;
      const action = this.mixer.clipAction(bound);
      action.enabled = true;
      this.actions.set(name, action);
    }
    if (resume && this.actions.has(resume)) this.play(resume);
    return true;
  }
  /**
   * Retarget a canonical-skeleton library clip onto the attached model's actual
   * bone names. Returns null when the rig shares too few bones to perform the
   * motion (a static prop, a non-humanoid rig), so callers skip building a dead
   * action. A clip whose tracks already match the rig 1:1 round-trips unchanged.
   * @param {THREE.AnimationClip} clip
   * @returns {THREE.AnimationClip|null}
   */
  _retarget(clip) {
    if (!this._canonicalToNode || this._canonicalToNode.size === 0) return null;
    let hipScale = 1;
    const baseline = clipHipBaselineY(clip);
    if (this._hipTargetLocalY > 0.05 && baseline > 0.05) {
      hipScale = Math.min(200, Math.max(0.2, this._hipTargetLocalY / baseline));
    }
    const { clip: out } = retargetClip(clip, this._canonicalToNode, {
      targetRest: this._canonicalRest,
      targetWorldRest: this._canonicalWorldRest,
      hipsParentWorldQuat: this._hipsParentWorldQuat,
      morphTargets: this._morphTargets,
      hipScale,
      hipOffsetY: this._hipGroundLocalY || 0
    });
    return out;
  }
  /**
   * Runtime "fallen pose" guard. Before an action plays, sample the at-rest Hips
   * world up-axis the retargeted clip would impose. If it tips past
   * CATASTROPHE_TILT_DEG the retarget failed (the rig would lie on its back), so
   * we disable that action, leave the rig in its authored bind pose — the same
   * fallback the viewer already prefers over a broken retarget — and report once
   * through the existing client-error channel with enough context to diagnose.
   * Sampled once per (avatar, clip), never per frame.
   *
   * @param {string} name clip name
   * @param {THREE.AnimationAction} action
   * @returns {boolean} true if the clip is safe to play; false if it was rejected
   */
  _guardAgainstFallenPose(name, action) {
    if (this._fallen.has(name)) return false;
    const clip = action?.getClip?.();
    const tiltDeg = measureHipsTiltDeg(clip, this.model, this._canonicalToNode);
    if (tiltDeg == null || !Number.isFinite(tiltDeg) || tiltDeg <= CATASTROPHE_TILT_DEG) return true;
    try {
      action.stop();
    } catch (e) {
      log.warn("[AnimationManager] failed to stop fallen-pose action:", e);
    }
    action.enabled = false;
    this.actions.delete(name);
    this._fallen.add(name);
    if (this.currentAction === action) {
      this.currentAction = null;
      this.currentName = null;
    }
    const avatarUrl = this._avatarContext.avatarUrl || "";
    const avatarId = this._avatarContext.avatarId || "";
    const dedupeKey = `${avatarId || avatarUrl}|${name}`;
    if (!this._fallenReported.has(dedupeKey)) {
      this._fallenReported.add(dedupeKey);
      log.warn(
        `[AnimationManager] "${name}" retargeted to a fallen pose (${tiltDeg.toFixed(1)}\xB0 off vertical) \u2014 falling back to bind pose`
      );
      reportFallenPose({
        avatarId,
        avatarUrl,
        clip: name,
        tiltDeg: Math.round(tiltDeg * 10) / 10
      });
    }
    return false;
  }
  /** Detach, stop all actions, dispose mixer. */
  detach() {
    if (this.mixer) {
      this.mixer.stopAllAction();
      this.mixer.uncacheRoot(this.mixer.getRoot());
      this.mixer = null;
    }
    this.model = null;
    this._canonicalToNode = null;
    this._canonicalRest = null;
    this._canonicalWorldRest = null;
    this._hipsParentWorldQuat = null;
    this._morphTargets = null;
    this._hipTargetLocalY = 0;
    this._hipGroundLocalY = 0;
    this._canonicalClipsSupported = false;
    this.actions.clear();
    this.currentAction = null;
    this.currentName = null;
    this._latestCrossfadeTarget = null;
    this._fallen.clear();
    this.overlayAction = null;
    this.overlayName = null;
    this._overlayClips.clear();
    this._overlayFinishHandler = null;
  }
  // ── Definitions ────────────────────────────────────────────────────────────
  /**
   * Register animation definitions (from manifest.json).
   * @param {Array<{name:string, url:string, label?:string, icon?:string, loop?:boolean}>} defs
   */
  setAnimationDefs(defs) {
    this._animationDefs = defs;
  }
  /**
   * Append additional defs (e.g. user/public clips from the API) without
   * replacing the manifest-sourced ones. Skips defs whose name is already
   * registered to avoid duplicates when called multiple times.
   * @param {Array<{name:string, url:string, label?:string, icon?:string, loop?:boolean}>} defs
   */
  appendAnimationDefs(defs) {
    const existing = new Set(this._animationDefs.map((d) => d.name));
    for (const def of defs) {
      if (!existing.has(def.name)) {
        this._animationDefs.push(def);
        existing.add(def.name);
      }
    }
  }
  /** @returns {Array} */
  getAnimationDefs() {
    return this._animationDefs;
  }
  /** @param {string} name @returns {boolean} */
  isFailed(name) {
    return this._failed.has(name);
  }
  /**
   * Whether the currently attached model can be driven by the pre-baked
   * canonical clip library. True only for a skinned humanoid whose skeleton
   * shares enough bones with the canonical Avaturn rig that a retargeted clip
   * actually moves it. A static mesh (no skeleton) or a non-humanoid rig
   * returns false, so callers can hide animation affordances that would
   * otherwise play to no visible effect.
   * @returns {boolean}
   */
  supportsCanonicalClips() {
    return this._canonicalClipsSupported;
  }
  /**
   * Guard against frozen T-pose arms. When the attached rig's upper-arm bones
   * could NOT be name-mapped, the retargeted clip drives the torso and legs but
   * leaves both arms stuck out at the authored bind pose. Swing those un-driven
   * arms down to a relaxed rest so the avatar never reads as broken. No-op when
   * the arms name-mapped (the clip already drives them) or no model is attached.
   * Call once, after attach() and before the first play/crossfade.
   * @returns {number} arms relaxed (0, 1, or 2)
   */
  relaxUndrivenArms() {
    if (!this.model) return 0;
    return relaxUndrivenArms(this.model, this._canonicalToNode);
  }
  /**
   * Whether a named clip can actually be driven on the attached rig: it's
   * either already loaded, or registered in the defs and not yet known to
   * have failed. Lets callers (e.g. the paid club stage) drop un-performable
   * steps from a routine before playback instead of crossfading to a silent
   * no-op and leaving the avatar frozen.
   * @param {string} name
   * @returns {boolean}
   */
  canPlay(name) {
    if (this._fallen.has(name)) return false;
    if (this.clips.has(name)) return true;
    if (this._failed.has(name)) return false;
    return this._animationDefs.some((d) => d.name === name);
  }
  // ── Loading ────────────────────────────────────────────────────────────────
  /**
   * Load a single clip from a pre-baked JSON URL and register it.
   * Idempotent — returns the cached clip if already loaded.
   *
   * Handles two response shapes:
   *  - Static clip JSON:  { name, duration, tracks }  (manifest clips)
   *  - API clip response: { clip: { clip: { name, duration, tracks } } }
   *    (/api/animations/clips/:id — detected by URL containing /api/animations/)
   *
   * @param {string} name
   * @param {string} url  URL to a clip JSON or /api/animations/clips/:id endpoint
   * @param {{ loop?: boolean }} [opts]
   * @returns {Promise<THREE.AnimationClip>}
   */
  async loadAnimation(name, url, opts = {}) {
    if (this.clips.has(name)) return this.clips.get(name);
    let pending = _clipCache.get(url);
    if (!pending) {
      pending = _AnimationManager._fetchClip(name, url);
      _clipCache.set(url, pending);
      pending.catch(() => {
        if (_clipCache.get(url) === pending) _clipCache.delete(url);
      });
    }
    const clip = await pending;
    return this._registerParsedClip(name, clip, opts);
  }
  /** @private Fetch + parse one clip JSON into a shared base AnimationClip. */
  static async _fetchClip(name, url) {
    const isApiClip = url.includes("/api/animations/");
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 15e3);
    let res;
    try {
      res = await fetch(url, {
        signal: controller.signal,
        // API clips need auth; static clips are same-origin assets. Use
        // 'same-origin' (not 'omit') so a credential-gated host proxy (e.g.
        // a private-forwarded Codespaces port) still receives the auth cookie
        // and serves the asset instead of 302-ing it to a login tunnel.
        credentials: isApiClip ? "include" : "same-origin",
        redirect: "error"
      });
    } finally {
      clearTimeout(timeoutId);
    }
    if (!res.ok) throw new Error(`HTTP ${res.status} loading animation ${name}`);
    const json = await res.json();
    const clipJson = isApiClip ? json?.clip?.clip : json;
    if (!clipJson && json?.clip?.paywalled) {
      throw new Error(`animation ${name} is a paid clip, unlock it at ${json.clip.download_url}`);
    }
    if (!clipJson) throw new Error(`clip payload missing from ${url}`);
    const clip = AnimationClip2.parse(clipJson);
    clip.name = name;
    return clip;
  }
  /**
   * Register a clip from already-fetched JSON — skips all network I/O.
   * Useful for callers that pre-fetch clip data alongside other parallel loads
   * so animations are ready before the first render frame.
   * @param {string} name
   * @param {object} clipJson  Raw AnimationClip JSON (same shape as the files in /animations/clips/)
   * @param {{ loop?: boolean }} [opts]
   */
  injectClip(name, clipJson, opts = {}) {
    if (!clipJson || this.clips.has(name)) return;
    try {
      const clip = AnimationClip2.parse(clipJson);
      clip.name = name;
      this._registerParsedClip(name, clip, opts);
    } catch (err) {
      log.warn(`[AnimationManager] injectClip "${name}" parse error:`, err.message);
    }
  }
  /** @private Shared finalization for both load paths. */
  _registerParsedClip(name, clip, opts) {
    this.clips.set(name, clip);
    if (this.model && this.mixer) {
      const bound = this._retarget(clip);
      if (bound) {
        const action = this.mixer.clipAction(bound);
        action.enabled = true;
        action.setLoop(opts.loop === false ? LoopOnce : LoopRepeat);
        if (opts.loop === false) action.clampWhenFinished = true;
        this.actions.set(name, action);
      }
    }
    return clip;
  }
  /**
   * Load all registered definitions in parallel.
   * Failed clips are logged and added to _failed; they do not throw.
   */
  async loadAll() {
    const CONCURRENCY = 4;
    const queue = [...this._animationDefs];
    const worker = async () => {
      let def;
      while (def = queue.shift()) {
        try {
          await this.loadAnimation(def.name, def.url, { loop: def.loop !== false });
        } catch (err) {
          log.warn(`[AnimationManager] failed to load "${def.name}":`, err.message);
          this._failed.add(def.name);
        }
      }
    };
    await Promise.all(Array.from({ length: CONCURRENCY }, worker));
  }
  /**
   * Lazily load a single clip by name (from registered defs) if not yet loaded.
   * Used so the first click on a strip button triggers a load without blocking startup.
   * @param {string} name
   * @returns {Promise<boolean>} true if ready
   */
  async ensureLoaded(name) {
    if (this.clips.has(name)) return true;
    if (this._failed.has(name)) return false;
    const def = this._animationDefs.find((d) => d.name === name);
    if (!def) return false;
    try {
      await this.loadAnimation(def.name, def.url, { loop: def.loop !== false });
      return true;
    } catch {
      this._failed.add(name);
      return false;
    }
  }
  // ── Playback ───────────────────────────────────────────────────────────────
  /**
   * Play a named clip immediately (hard cut, no crossfade).
   * Lazily loads if not yet in memory.
   * @param {string} name
   * @returns {Promise<boolean>} true if the clip started playing, false if unavailable or rejected
   */
  async play(name) {
    const ready = await this.ensureLoaded(name);
    if (!ready) {
      if (this._failed.has(name) || this._animationDefs.some((d) => d.name === name))
        log.warn(`[AnimationManager] "${name}" unavailable`);
      return false;
    }
    if (name === this.currentName) return true;
    const action = this.actions.get(name);
    if (!action) return false;
    if (!this._guardAgainstFallenPose(name, action)) return false;
    if (this.currentAction && this.currentAction !== action) {
      this.currentAction.fadeOut(0.01);
    }
    action.reset().fadeIn(0.01).play();
    this.currentAction = action;
    this.currentName = name;
    try {
      this.onChange?.(name);
    } catch (e) {
      log.warn("[AnimationManager] onChange threw:", e);
    }
    return true;
  }
  /**
   * Play a one-shot clip exactly once, then settle into a looping clip
   * (`idle` by default) with a crossfade — instead of clamping and freezing on
   * the final frame, which reads as a hard snap on a looping thumbnail. This is
   * the seamless path for manifest clips whose `loop` field is false
   * (`celebrate`, `wave`, …). Lazily loads both the one-shot and the settle clip.
   *
   * @param {string} name
   * @param {{ settleTo?: string|null, fade?: number }} [opts]
   */
  async playOnce(name, { settleTo = "idle", fade = DEFAULT_CROSSFADE } = {}) {
    fade = Math.max(0, Math.min(fade, 5));
    const ready = await this.ensureLoaded(name);
    const action = ready ? this.actions.get(name) : null;
    if (!action || !this._guardAgainstFallenPose(name, action)) {
      if (settleTo) return this.crossfadeTo(settleTo, fade);
      return;
    }
    action.reset();
    action.setLoop(LoopOnce, 1);
    action.clampWhenFinished = true;
    action.play();
    if (this.currentAction && this.currentAction !== action) {
      this.currentAction.crossFadeTo(action, fade, true);
    } else {
      action.fadeIn(fade);
    }
    this.currentAction = action;
    this.currentName = name;
    if (this._settleFinishHandler && this.mixer) {
      this.mixer.removeEventListener("finished", this._settleFinishHandler);
      this._settleFinishHandler = null;
    }
    if (settleTo && this.mixer) {
      const onFinished = (e) => {
        if (e.action !== action) return;
        this.mixer.removeEventListener("finished", onFinished);
        if (this._settleFinishHandler === onFinished) this._settleFinishHandler = null;
        if (this.currentAction === action) this.crossfadeTo(settleTo, fade);
      };
      this._settleFinishHandler = onFinished;
      this.mixer.addEventListener("finished", onFinished);
    }
    try {
      this.onChange?.(name);
    } catch (e) {
      log.warn("[AnimationManager] onChange threw:", e);
    }
  }
  /**
   * Freeze the active clip on its current pose and release it as the active
   * action so the host viewer's render loop can settle (it stops scheduling
   * frames once nothing is animating). The paused action keeps applying its
   * held pose whenever the mixer does tick again (e.g. on scroll-back-in), so
   * the avatar shows a clean static pose rather than a T-pose.
   *
   * Used for `prefers-reduced-motion`: a calm held pose with no looping motion
   * and no continuous GPU/CPU cost.
   */
  freeze() {
    if (this.currentAction) this.currentAction.paused = true;
    this.currentAction = null;
    this.currentName = null;
    this._latestCrossfadeTarget = null;
  }
  /**
   * Crossfade from the current clip to a named clip.
   * Lazily loads if not yet in memory.
   * @param {string} name
   * @param {number} [duration] seconds
   */
  async crossfadeTo(name, duration = DEFAULT_CROSSFADE) {
    duration = Math.max(0, Math.min(duration, 5));
    if (name === this.currentName) return;
    this._latestCrossfadeTarget = name;
    const ready = await this.ensureLoaded(name);
    if (!ready) {
      if (this._failed.has(name) || this._animationDefs.some((d) => d.name === name))
        log.warn(`[AnimationManager] "${name}" unavailable`);
      return;
    }
    if (this._latestCrossfadeTarget !== name) return;
    if (name === this.currentName) return;
    const next = this.actions.get(name);
    if (!next) return;
    if (!this._guardAgainstFallenPose(name, next)) return;
    next.reset().play();
    if (this.currentAction && this.currentAction !== next) {
      this.currentAction.crossFadeTo(next, duration, true);
    } else {
      next.fadeIn(duration);
    }
    this.currentAction = next;
    this.currentName = name;
    try {
      this.onChange?.(name);
    } catch (e) {
      log.warn("[AnimationManager] onChange threw:", e);
    }
  }
  // ── Additive overlay (gesture-over-locomotion) ──────────────────────────────
  /**
   * Build (and cache) an additive clip for the named library clip, retargeted to
   * the attached rig. The clip is made additive relative to its own first frame —
   * the standard three.js technique (see webgl_animation_skinning_additive_blending) —
   * so playing it adds its pose delta on top of whatever the base layer is doing.
   * When `upperBodyOnly` is set, every lower-body track (hips, legs, feet) is
   * dropped first so locomotion keeps full control of the legs.
   *
   * @param {string} name
   * @param {boolean} upperBodyOnly
   * @returns {THREE.AnimationClip|null}
   */
  _buildOverlayClip(name, upperBodyOnly) {
    const cacheKey = `${name}|${upperBodyOnly ? "upper" : "full"}`;
    const cached = this._overlayClips.get(cacheKey);
    if (cached) return cached;
    const raw = this.clips.get(name);
    if (!raw) return null;
    const retargeted = this._retarget(raw);
    if (!retargeted) return null;
    let tracks = retargeted.tracks;
    if (upperBodyOnly && this._canonicalToNode) {
      const lowerNodes = /* @__PURE__ */ new Set();
      for (const canonical of LOWER_BODY_CANONICAL) {
        const node = this._canonicalToNode.get(canonical);
        if (node) lowerNodes.add(node);
      }
      tracks = tracks.filter((t) => {
        const node = t.name.split(".")[0];
        return !lowerNodes.has(node);
      });
      if (tracks.length === 0) return null;
    }
    const overlay = new AnimationClip2(`${name}__additive`, retargeted.duration, tracks);
    AnimationUtils.makeClipAdditive(overlay);
    this._overlayClips.set(cacheKey, overlay);
    return overlay;
  }
  /**
   * Play a gesture as an additive overlay on top of the current base clip, so the
   * avatar can (e.g.) wave or cheer while it keeps walking. The base
   * idle/walk/run action is untouched — only this additive layer is added.
   *
   * @param {string} name clip name (library or appended def)
   * @param {{ loop?: boolean, crossfade?: number, upperBodyOnly?: boolean, timeScale?: number, onFinished?: Function }} [opts]
   * @returns {Promise<boolean>} true if the overlay started
   */
  async playOverlay(name, { loop = false, crossfade = 0.25, upperBodyOnly = true, timeScale = 1, onFinished = null } = {}) {
    crossfade = Math.max(0, Math.min(crossfade, 5));
    const ready = await this.ensureLoaded(name);
    if (!ready || !this.mixer) return false;
    const overlayClip = this._buildOverlayClip(name, upperBodyOnly);
    if (!overlayClip) return false;
    const next = this.mixer.clipAction(overlayClip, void 0, AdditiveAnimationBlendMode);
    this._detachOverlayFinish();
    const prev = this.overlayAction;
    if (prev && prev !== next) prev.fadeOut(crossfade);
    next.enabled = true;
    next.setLoop(loop ? LoopRepeat : LoopOnce, loop ? Infinity : 1);
    next.clampWhenFinished = !loop;
    next.setEffectiveTimeScale(timeScale);
    next.reset();
    next.setEffectiveWeight(1);
    next.fadeIn(crossfade);
    next.play();
    this.overlayAction = next;
    this.overlayName = name;
    if (!loop) {
      const handler = (e) => {
        if (e.action !== next) return;
        this.mixer.removeEventListener("finished", handler);
        if (this._overlayFinishHandler === handler) this._overlayFinishHandler = null;
        if (this.overlayAction === next) {
          next.fadeOut(crossfade);
          this.overlayAction = null;
          this.overlayName = null;
        }
        try {
          onFinished?.(name);
        } catch (err) {
          log.warn("[AnimationManager] overlay onFinished threw:", err);
        }
      };
      this._overlayFinishHandler = handler;
      this.mixer.addEventListener("finished", handler);
    }
    return true;
  }
  /** @private Detach the pending one-shot overlay 'finished' listener. */
  _detachOverlayFinish() {
    if (this._overlayFinishHandler && this.mixer) {
      this.mixer.removeEventListener("finished", this._overlayFinishHandler);
    }
    this._overlayFinishHandler = null;
  }
  /**
   * Fade out and clear the active additive overlay, returning the avatar to the
   * pure base layer. No-op when no overlay is playing.
   * @param {{ crossfade?: number }} [opts]
   */
  stopOverlay({ crossfade = 0.2 } = {}) {
    crossfade = Math.max(0, Math.min(crossfade, 5));
    this._detachOverlayFinish();
    if (this.overlayAction) {
      this.overlayAction.fadeOut(crossfade);
      this.overlayAction = null;
      this.overlayName = null;
    }
  }
  /** @returns {boolean} whether an additive overlay is currently playing. */
  hasOverlay() {
    return this.overlayAction != null;
  }
  /**
   * Scale playback speed of the active clip. 1 = normal; >1 plays faster
   * (used to make a walk cycle read as a run).
   * @param {number} scale
   */
  setSpeed(scale) {
    this.currentAction?.setEffectiveTimeScale(scale);
  }
  /** Stop all animations. */
  stopAll() {
    this.mixer?.stopAllAction();
    this._detachOverlayFinish();
    this.currentAction = null;
    this.currentName = null;
    this.overlayAction = null;
    this.overlayName = null;
    try {
      this.onChange?.(null);
    } catch (e) {
      log.warn("[AnimationManager] onChange threw:", e);
    }
  }
  /**
   * Tick the mixer. Call from the render loop.
   * @param {number} delta seconds since last frame
   */
  update(delta) {
    this.mixer?.update(delta);
  }
  /** @returns {string[]} */
  getLoadedNames() {
    return [...this.clips.keys()];
  }
  /** @param {string} name @returns {boolean} */
  isLoaded(name) {
    return this.clips.has(name);
  }
  dispose() {
    this.detach();
    this.clips.clear();
    this._animationDefs = [];
    this._failed.clear();
  }
};
function _modelSupportsCanonicalClips(model) {
  let hasSkinnedMesh = false;
  const canonical = /* @__PURE__ */ new Set();
  model.traverse((node) => {
    if (node.isSkinnedMesh) hasSkinnedMesh = true;
    if (node.name) {
      const c = canonicalizeBoneName(node.name);
      if (c) canonical.add(c);
    }
  });
  return hasSkinnedMesh && canonical.size >= MIN_CANONICAL_BONES;
}
function reportFallenPose(context) {
  try {
    const hook = typeof window !== "undefined" && typeof window.reportClientError === "function" ? window.reportClientError : null;
    if (!hook) return;
    hook(new Error("fallen-pose retarget"), context);
  } catch (e) {
    log.warn("[AnimationManager] fallen-pose report failed:", e);
  }
}
export {
  AnimationManager,
  CANONICAL_BONES,
  CANONICAL_REST,
  CANONICAL_REST_WORLD,
  MIN_COVERAGE,
  canonicalNodeMapFromObject,
  canonicalNodeMapFromRig,
  canonicalRestMapFromObject,
  canonicalRestMapFromRig,
  canonicalWorldRestMapFromObject,
  canonicalWorldRestMapFromRig,
  canonicalizeArmatureOrientation,
  canonicalizeBoneName,
  canonicalizeGLBBones,
  canonicalizeJointNodes,
  clipHipBaselineY,
  hipRestHeight,
  hipRestLocalHeight,
  hipsParentWorldQuat,
  measureHipsTiltDeg,
  parseClipJSON,
  retargetClip,
  retargetClipToObject,
  retargetClipToRig,
  scaleClipSpeed
};
