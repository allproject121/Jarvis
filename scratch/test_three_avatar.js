const fs = require('fs');

// Verify that the files exist and have non-zero size
const avatarPath = 'app/src/main/assets/models/jett.glb';
const animPath = 'app/src/main/assets/models/animations.glb';

console.log('Testing asset presence:');
console.log('jett.glb size:', fs.statSync(avatarPath).size, 'bytes');
console.log('animations.glb size:', fs.statSync(animPath).size, 'bytes');

// Check that GLTFLoader.js is in assets
const loaderPath = 'app/src/main/assets/GLTFLoader.js';
console.log('GLTFLoader.js exists:', fs.existsSync(loaderPath));
