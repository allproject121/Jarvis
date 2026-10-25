const fs = require('fs');
const code = fs.readFileSync('app/src/main/assets/three.min.js', 'utf8');
const context = {};
const fn = new Function('window', 'self', 'global', code + '\nreturn THREE;');
const THREE = fn(context, context, context);

const camera = new THREE.PerspectiveCamera(40, 1.14, 0.1, 100);
camera.position.set(0, 0.86, 2.30);
camera.lookAt(0, 0.68, 0);
camera.updateMatrixWorld();
camera.updateProjectionMatrix();

function toNdc(y) {
  const v = new THREE.Vector3(0, y, 0);
  v.project(camera);
  return v.y;
}

console.log('--- Non-fullscreen (aspect 1.14), Cam (0, 0.86, 2.30), Look (0, 0.68, 0) ---');
console.log('Head Top (Y=1.65): NDC =', toNdc(1.65).toFixed(3));
console.log('Eyes (Y=1.45):     NDC =', toNdc(1.45).toFixed(3));
console.log('Feet (Y=0.0):      NDC =', toNdc(0.0).toFixed(3));
console.log('Base (Y=-0.32):    NDC =', toNdc(-0.32).toFixed(3));
