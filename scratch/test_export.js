const fs = require('fs');
const path = require('path');

const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { GLTFExporter } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/exporters/GLTFExporter.js'));

async function testExport() {
  const { convertAll } = require('./build_mora_library.js');
  // wait for clips
  // We can write an exporter script that takes the clips and exports them to GLB or JSON!
}
