const fs = require('fs');

console.log('Building retargeted animations for Hinata...');

const animBuf = fs.readFileSync('app/src/main/assets/models/animations.glb');
const jsonLen = animBuf.readUInt32LE(12);
const json = JSON.parse(animBuf.slice(20, 20 + jsonLen).toString('utf8'));
const binOffset = 20 + jsonLen + 8;
const binBuf = animBuf.slice(binOffset);

// Mapping from Mixamo bone name to VRM bone name
const BONE_MAP = {
  'Hips': 'J_Bip_C_Hips',
  'Spine': 'J_Bip_C_Spine',
  'Spine1': 'J_Bip_C_Chest',
  'Spine2': 'J_Bip_C_UpperChest',
  'Neck': 'J_Bip_C_Neck',
  'Head': 'J_Bip_C_Head',
  'LeftShoulder': 'J_Bip_L_Shoulder',
  'LeftArm': 'J_Bip_L_UpperArm',
  'LeftForeArm': 'J_Bip_L_LowerArm',
  'LeftHand': 'J_Bip_L_Hand',
  'RightShoulder': 'J_Bip_R_Shoulder',
  'RightArm': 'J_Bip_R_UpperArm',
  'RightForeArm': 'J_Bip_R_LowerArm',
  'RightHand': 'J_Bip_R_Hand',
  'LeftUpLeg': 'J_Bip_L_UpperLeg',
  'LeftLeg': 'J_Bip_L_LowerLeg',
  'LeftFoot': 'J_Bip_L_Foot',
  'LeftToeBase': 'J_Bip_L_ToeBase',
  'RightUpLeg': 'J_Bip_R_UpperLeg',
  'RightLeg': 'J_Bip_R_LowerLeg',
  'RightFoot': 'J_Bip_R_Foot',
  'RightToeBase': 'J_Bip_R_ToeBase',

  // Left Fingers
  'LeftHandThumb1': 'J_Bip_L_Thumb1',
  'LeftHandThumb2': 'J_Bip_L_Thumb2',
  'LeftHandThumb3': 'J_Bip_L_Thumb3',
  'LeftHandIndex1': 'J_Bip_L_Index1',
  'LeftHandIndex2': 'J_Bip_L_Index2',
  'LeftHandIndex3': 'J_Bip_L_Index3',
  'LeftHandMiddle1': 'J_Bip_L_Middle1',
  'LeftHandMiddle2': 'J_Bip_L_Middle2',
  'LeftHandMiddle3': 'J_Bip_L_Middle3',
  'LeftHandRing1': 'J_Bip_L_Ring1',
  'LeftHandRing2': 'J_Bip_L_Ring2',
  'LeftHandRing3': 'J_Bip_L_Ring3',
  'LeftHandPinky1': 'J_Bip_L_Little1',
  'LeftHandPinky2': 'J_Bip_L_Little2',
  'LeftHandPinky3': 'J_Bip_L_Little3',

  // Right Fingers
  'RightHandThumb1': 'J_Bip_R_Thumb1',
  'RightHandThumb2': 'J_Bip_R_Thumb2',
  'RightHandThumb3': 'J_Bip_R_Thumb3',
  'RightHandIndex1': 'J_Bip_R_Index1',
  'RightHandIndex2': 'J_Bip_R_Index2',
  'RightHandIndex3': 'J_Bip_R_Index3',
  'RightHandMiddle1': 'J_Bip_R_Middle1',
  'RightHandMiddle2': 'J_Bip_R_Middle2',
  'RightHandMiddle3': 'J_Bip_R_Middle3',
  'RightHandRing1': 'J_Bip_R_Ring1',
  'RightHandRing2': 'J_Bip_R_Ring2',
  'RightHandRing3': 'J_Bip_R_Ring3',
  'RightHandPinky1': 'J_Bip_R_Little1',
  'RightHandPinky2': 'J_Bip_R_Little2',
  'RightHandPinky3': 'J_Bip_R_Little3'
};

// Check nodes in animations.glb
let mappedCount = 0;
json.nodes.forEach((n, idx) => {
  const vrmName = BONE_MAP[n.name];
  if (vrmName) {
    mappedCount++;
    n.name = vrmName;
  }
});

console.log('Mapped', mappedCount, 'nodes directly to VRM bone names in animation library');

// Also load hinata.glb to verify every mapped node exists in hinata.glb
const hinataBuf = fs.readFileSync('app/src/main/assets/models/hinata.glb');
const hinataJsonLen = hinataBuf.readUInt32LE(12);
const hinataJson = JSON.parse(hinataBuf.slice(20, 20 + hinataJsonLen).toString('utf8'));
const hinataNodes = new Set(hinataJson.nodes.map(n => n.name));

let matchCount = 0;
json.nodes.forEach(n => {
  if (hinataNodes.has(n.name)) matchCount++;
});
console.log('Matching animation nodes present in Hinata model:', matchCount, '/', json.nodes.length);

// Save retargeted animations GLB
let newJsonStr = JSON.stringify(json);
let newJsonBuf = Buffer.from(newJsonStr, 'utf8');
const pad4 = (n) => (n + 3) & ~3;
const jsonPaddedLen = pad4(newJsonBuf.length);
if (jsonPaddedLen > newJsonBuf.length) {
  newJsonBuf = Buffer.concat([newJsonBuf, Buffer.alloc(jsonPaddedLen - newJsonBuf.length, 0x20)]);
}

const totalGlbLen = 12 + (8 + newJsonBuf.length) + (8 + binBuf.length);
const header = Buffer.alloc(12);
header.writeUInt32LE(0x46546C67, 0);
header.writeUInt32LE(2, 4);
header.writeUInt32LE(totalGlbLen, 8);

const jsonChunkHeader = Buffer.alloc(8);
jsonChunkHeader.writeUInt32LE(newJsonBuf.length, 0);
jsonChunkHeader.writeUInt32LE(0x4E4F534A, 4);

const binChunkHeader = Buffer.alloc(8);
binChunkHeader.writeUInt32LE(binBuf.length, 0);
binChunkHeader.writeUInt32LE(0x004E4942, 4);

const outBuf = Buffer.concat([
  header,
  jsonChunkHeader,
  newJsonBuf,
  binChunkHeader,
  binBuf
]);

const outPath = 'app/src/main/assets/models/hinata_animations.glb';
fs.writeFileSync(outPath, outBuf);
console.log('Saved', outPath, outBuf.length, 'bytes');
