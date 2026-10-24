const fs = require('fs');

const moraHtmlPath = 'app/src/main/assets/mora_character.html';
let html = fs.readFileSync(moraHtmlPath, 'utf8');

// 1. Store tick materials
html = html.replace(
  /\/\/ 5\. Holographic Radial Ticks around pedestal rim\s*for \(let i = 0; i < 12; i\+\+\) \{[\s\S]*?stageGroup\.add\(tickMesh\);\s*\}/,
  `// 5. Holographic Radial Ticks around pedestal rim
    const tickMaterials = [];
    for (let i = 0; i < 12; i++) {
      const angle = (i / 12) * Math.PI * 2;
      const tickGeo = new THREE.PlaneGeometry(0.012, 0.042);
      const tickMat = new THREE.MeshBasicMaterial({
        color: (i % 3 === 0) ? 0x00f0ff : 0xff0055,
        side: THREE.DoubleSide,
        transparent: true,
        opacity: 0.75
      });
      tickMaterials.push(tickMat);
      const tickMesh = new THREE.Mesh(tickGeo, tickMat);
      tickMesh.rotation.x = -Math.PI / 2;
      tickMesh.rotation.z = angle;
      tickMesh.position.set(Math.cos(angle) * 0.44, 0.0025, Math.sin(angle) * 0.44);
      stageGroup.add(tickMesh);
    }`
);

// 2. Add applyMoraTheme function before MoraCharacterController
const themeFn = `
    function applyMoraTheme(themeKey, customHex) {
      const key = (themeKey || "").toLowerCase();
      let primary = 0xff0055;   // Default REDMAGIC magenta
      let secondary = 0x00f0ff; // Default cyber cyan
      let particle = 0xff0055;

      if (customHex && typeof customHex === "string" && customHex.startsWith("#")) {
        try {
          const c = new THREE.Color(customHex);
          primary = c.getHex();
          secondary = 0xffffff;
          particle = c.getHex();
        } catch (_){}
      }
      
      if (key === "red" || key === "crimson" || key === "ruby") {
        primary = 0xff1744;   // Vibrant Electric Red
        secondary = 0xff5252; // Neon Coral Red
        particle = 0xff1744;  // Red sparks
      } else if (key === "pearl_white" || key === "white" || key === "light") {
        primary = 0xffffff;   // Clean Pearl White
        secondary = 0x38bdf8; // Radiant Ice Blue
        particle = 0xffffff;  // White cosmic dust
      } else if (key === "emerald" || key === "green" || key === "lime") {
        primary = 0x10b981;
        secondary = 0x34d399;
        particle = 0x10b981;
      } else if (key === "gold" || key === "amber") {
        primary = 0xf59e0b;
        secondary = 0xfbbf24;
        particle = 0xf59e0b;
      } else if (key === "amethyst" || key === "purple" || key === "violet") {
        primary = 0xc084fc;
        secondary = 0xa855f7;
        particle = 0xc084fc;
      } else if (key === "pink" || key === "rose") {
        primary = 0xec4899;
        secondary = 0xf472b6;
        particle = 0xec4899;
      } else if (key === "sunset") {
        primary = 0xff6a3d;
        secondary = 0xfb7185;
        particle = 0xff6a3d;
      } else if (key === "stealth") {
        primary = 0x38bdf8;
        secondary = 0x64748b;
        particle = 0x38bdf8;
      }

      if (rimMat) rimMat.color.setHex(secondary);
      if (ringMagentaMat) ringMagentaMat.color.setHex(primary);
      if (ringCyanMat) ringCyanMat.color.setHex(secondary);
      if (pMat) pMat.color.setHex(particle);
      tickMaterials.forEach((tm, idx) => {
        if (tm && tm.color) tm.color.setHex(idx % 2 === 0 ? primary : secondary);
      });
      console.log("Mora theme applied:", key, customHex);
    }
`;

if (!html.includes('function applyMoraTheme')) {
  html = html.replace(
    /\/\* =========================================================================\s*MoraCharacterController & Expression \/ Lip-Sync System/,
    `${themeFn}\n    /* =========================================================================\n       MoraCharacterController & Expression / Lip-Sync System`
  );
}

// 3. Update bridge setTheme and setPreset
html = html.replace(
  /setTheme:\s*\(\)\s*=>\s*\{\},\s*setPreset:\s*\(\)\s*=>\s*\{\},/,
  `setTheme: (theme, custom) => applyMoraTheme(theme, custom),\n      setPreset: (preset, custom) => applyMoraTheme(preset, custom),`
);

// 4. Initial theme from query params
if (!html.includes('const urlParams = new URLSearchParams')) {
  html = html.replace(
    /console\.log\("Mora Companion & JarvisOrb Bridges initialized\."\);/,
    `try {
        const urlParams = new URLSearchParams(window.location.search);
        const initTheme = urlParams.get("theme") || "dark";
        const initHex = urlParams.get("hex") || "";
        applyMoraTheme(initTheme, initHex);
      } catch (e) {
        console.warn("Error reading theme query params:", e);
      }
      console.log("Mora Companion & JarvisOrb Bridges initialized.");`
  );
}

fs.writeFileSync(moraHtmlPath, html, 'utf8');
console.log('mora_character.html updated successfully with dynamic theme support!');
