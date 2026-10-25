const fs = require('fs');

// Test that three.js and canvas operations run cleanly in node
const THREE = require('../research_repos/Anima/frontend/node_modules/three');
console.log('THREE version:', THREE.REVISION);

// Test cylinder and shadow geometries
const stageRadius = 0.65;
const topGeo = new THREE.CylinderGeometry(stageRadius, stageRadius * 1.02, 0.04, 48);
const baseGeo = new THREE.CylinderGeometry(stageRadius * 1.02, stageRadius * 1.15, 0.22, 48);
console.log('Pedestal geometries created cleanly');
