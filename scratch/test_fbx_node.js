const fs = require('fs');
const path = require('path');

const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { FBXLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/FBXLoader.js'));

const fbxLoader = new FBXLoader();
const fbxBuf = fs.readFileSync('research_repos/anime-ai/humanoid-animations/Laughing.fbx');
const arrayBuf = fbxBuf.buffer.slice(fbxBuf.byteOffset, fbxBuf.byteOffset + fbxBuf.byteLength);

try {
  const fbxAsset = fbxLoader.parse(arrayBuf, '');
  console.log('Laughing.fbx parsed successfully!');
  console.log('Animations:', fbxAsset.animations.map(a => ({ name: a.name, duration: a.duration, tracks: a.tracks.length })));
} catch (e) {
  console.error('FBX parse error:', e.message);
}
