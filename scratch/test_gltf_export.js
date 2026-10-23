const fs = require('fs');
const path = require('path');

const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { GLTFExporter } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/exporters/GLTFExporter.js'));
const { GLTFLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/GLTFLoader.js'));
const { FBXLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/FBXLoader.js'));

// Read build_mora_library convert function
const moraBuf = fs.readFileSync('app/src/main/assets/models/mora.glb');
const moraArrayBuf = moraBuf.buffer.slice(moraBuf.byteOffset, moraBuf.byteOffset + moraBuf.byteLength);

// Let's test GLTFExporter with a dummy scene and clips
const scene = new THREE.Scene();
const root = new THREE.Group();
root.name = 'MoraArmature';
scene.add(root);

const exporter = new GLTFExporter();
console.log('GLTFExporter available:', typeof exporter.parse);
