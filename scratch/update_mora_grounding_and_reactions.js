const fs = require('fs');

const moraHtmlPath = 'app/src/main/assets/mora_character.html';
let html = fs.readFileSync(moraHtmlPath, 'utf8');

// 1. Replace the thin stage with a Solid Architectural Cyber Platform Dock
const oldStageRegex = /\/\* =========================================================================\s*REDMAGIC 3D Cyber Ground Pedestal \/ Stage[\s\S]*?\/\/ 6\. Floating Cyber Energy Particles/;

const newStageCode = `/* =========================================================================
       REDMAGIC 3D Cyber Ground Pedestal / Architectural Standing Dock
       Solid physical command platform anchoring Mora firmly to the ground.
       ========================================================================= */
    const stageGroup = new THREE.Group();
    scene.add(stageGroup);

    const stageRadius = 0.65;

    // 1. Solid Top Metallic Deck Plate (Top surface flush at Y = 0)
    const topDeckGeo = new THREE.CylinderGeometry(stageRadius, stageRadius * 1.02, 0.04, 48);
    const deckMat = new THREE.MeshStandardMaterial({
      color: 0x090e1a,
      roughness: 0.28,
      metalness: 0.85
    });
    const topDeckMesh = new THREE.Mesh(topDeckGeo, deckMat);
    topDeckMesh.position.y = -0.02;
    topDeckMesh.receiveShadow = true;
    stageGroup.add(topDeckMesh);

    // 2. Heavy Architectural Sub-Base (Extends deeply downwards to Y = -0.22)
    const subBaseGeo = new THREE.CylinderGeometry(stageRadius * 1.02, stageRadius * 1.16, 0.20, 48);
    const subBaseMat = new THREE.MeshStandardMaterial({
      color: 0x04060c,
      roughness: 0.42,
      metalness: 0.92
    });
    const subBaseMesh = new THREE.Mesh(subBaseGeo, subBaseMat);
    subBaseMesh.position.y = -0.14;
    stageGroup.add(subBaseMesh);

    // 3. Foundation Floor Anchor Plinth (Anchors firmly to Y = -0.36)
    const plinthGeo = new THREE.CylinderGeometry(stageRadius * 1.16, stageRadius * 1.25, 0.16, 48);
    const plinthMat = new THREE.MeshStandardMaterial({
      color: 0x020306,
      roughness: 0.55,
      metalness: 0.96
    });
    const plinthMesh = new THREE.Mesh(plinthGeo, plinthMat);
    plinthMesh.position.y = -0.32;
    stageGroup.add(plinthMesh);

    // 4. Beveled Outer Glow Rim (LED Edge)
    const rimGeo = new THREE.TorusGeometry(stageRadius, 0.008, 16, 64);
    const rimMat = new THREE.MeshBasicMaterial({ color: 0x00f0ff });
    const rimMesh = new THREE.Mesh(rimGeo, rimMat);
    rimMesh.rotation.x = Math.PI / 2;
    rimMesh.position.y = 0.001;
    stageGroup.add(rimMesh);

    // 5. Anti-Slip Foot Placement Dock Pads (Underneath Mora's left & right platform shoes)
    const padGeo = new THREE.PlaneGeometry(0.12, 0.24);
    const padMat = new THREE.MeshStandardMaterial({
      color: 0x0f172a,
      roughness: 0.45,
      metalness: 0.75,
      side: THREE.DoubleSide
    });

    const padAccentMat = new THREE.MeshBasicMaterial({
      color: 0x00f0ff,
      side: THREE.DoubleSide,
      transparent: true,
      opacity: 0.65
    });

    const leftPad = new THREE.Mesh(padGeo, padMat);
    leftPad.rotation.x = -Math.PI / 2;
    leftPad.rotation.z = 0.035;
    leftPad.position.set(-0.105, 0.0012, 0.015);
    stageGroup.add(leftPad);

    const rightPad = new THREE.Mesh(padGeo, padMat);
    rightPad.rotation.x = -Math.PI / 2;
    rightPad.rotation.z = -0.035;
    rightPad.position.set(0.105, 0.0012, 0.015);
    stageGroup.add(rightPad);

    // 6. Dedicated Dual Contact Ambient Occlusion Shadows (Direct contact under each shoe sole)
    const footShadowCanvas = document.createElement("canvas");
    footShadowCanvas.width = 128;
    footShadowCanvas.height = 256;
    const fsCtx = footShadowCanvas.getContext("2d");
    const fsGrad = fsCtx.createRadialGradient(64, 128, 6, 64, 128, 58);
    fsGrad.addColorStop(0, "rgba(0, 0, 0, 0.95)");
    fsGrad.addColorStop(0.35, "rgba(0, 0, 0, 0.75)");
    fsGrad.addColorStop(0.75, "rgba(0, 0, 0, 0.30)");
    fsGrad.addColorStop(1, "rgba(0, 0, 0, 0.0)");
    fsCtx.fillStyle = fsGrad;
    fsCtx.fillRect(0, 0, 128, 256);
    const footShadowTex = new THREE.CanvasTexture(footShadowCanvas);

    const footShadowGeo = new THREE.PlaneGeometry(0.13, 0.26);
    const footShadowMat = new THREE.MeshBasicMaterial({
      map: footShadowTex,
      transparent: true,
      opacity: 0.95,
      depthWrite: false
    });

    const leftFootShadow = new THREE.Mesh(footShadowGeo, footShadowMat);
    leftFootShadow.rotation.x = -Math.PI / 2;
    leftFootShadow.rotation.z = 0.035;
    leftFootShadow.position.set(-0.105, 0.002, 0.015);
    stageGroup.add(leftFootShadow);

    const rightFootShadow = new THREE.Mesh(footShadowGeo, footShadowMat.clone());
    rightFootShadow.rotation.x = -Math.PI / 2;
    rightFootShadow.rotation.z = -0.035;
    rightFootShadow.position.set(0.105, 0.002, 0.015);
    stageGroup.add(rightFootShadow);

    // 7. Broad Ambient Body Shadow Disc
    const shadowCanvas = document.createElement("canvas");
    shadowCanvas.width = 128;
    shadowCanvas.height = 128;
    const sCtx = shadowCanvas.getContext("2d");
    const grad = sCtx.createRadialGradient(64, 64, 4, 64, 64, 62);
    grad.addColorStop(0, "rgba(0, 0, 0, 0.85)");
    grad.addColorStop(0.45, "rgba(0, 0, 0, 0.45)");
    grad.addColorStop(1, "rgba(0, 0, 0, 0.0)");
    sCtx.fillStyle = grad;
    sCtx.fillRect(0, 0, 128, 128);
    const shadowTex = new THREE.CanvasTexture(shadowCanvas);
    const shadowGeo = new THREE.PlaneGeometry(0.70, 0.70);
    const shadowMat = new THREE.MeshBasicMaterial({
      map: shadowTex,
      transparent: true,
      depthWrite: false
    });
    const contactShadow = new THREE.Mesh(shadowGeo, shadowMat);
    contactShadow.rotation.x = -Math.PI / 2;
    contactShadow.position.y = 0.0018;
    stageGroup.add(contactShadow);

    // 8. Concentric Neon Counter-Rotating Pulse Rings
    const ringMagentaGeo = new THREE.RingGeometry(0.36, 0.38, 48);
    const ringMagentaMat = new THREE.MeshBasicMaterial({
      color: 0xff0055,
      side: THREE.DoubleSide,
      transparent: true,
      opacity: 0.85
    });
    const ringMagenta = new THREE.Mesh(ringMagentaGeo, ringMagentaMat);
    ringMagenta.rotation.x = -Math.PI / 2;
    ringMagenta.position.y = 0.0025;
    stageGroup.add(ringMagenta);

    const ringCyanGeo = new THREE.RingGeometry(0.50, 0.518, 48);
    const ringCyanMat = new THREE.MeshBasicMaterial({
      color: 0x00f0ff,
      side: THREE.DoubleSide,
      transparent: true,
      opacity: 0.75
    });
    const ringCyan = new THREE.Mesh(ringCyanGeo, ringCyanMat);
    ringCyan.rotation.x = -Math.PI / 2;
    ringCyan.position.y = 0.0025;
    stageGroup.add(ringCyan);

    // 9. Holographic Radial Ticks around pedestal rim
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
      tickMesh.position.set(Math.cos(angle) * 0.44, 0.0028, Math.sin(angle) * 0.44);
      stageGroup.add(tickMesh);
    }

    // 6. Floating Cyber Energy Particles`;

html = html.replace(oldStageRegex, newStageCode);

// 2. Update applyMoraTheme to also color padAccentMat
if (!html.includes('if (padAccentMat)')) {
  html = html.replace(
    'if (rimMat) rimMat.color.setHex(secondary);',
    'if (rimMat) rimMat.color.setHex(secondary);\n      if (padAccentMat) padAccentMat.color.setHex(secondary);'
  );
}

// 3. Update Camera Framing for optimal grounded perspective
html = html.replace(
  /if \(aspect < 0\.65\) \{[\s\S]*?camera\.position\.set\(0, 0\.92, 2\.62\);[\s\S]*?camera\.lookAt\(0, 0\.72, 0\);[\s\S]*?\}/,
  `if (aspect < 0.65) {
        // Tall portrait (smartphones) - grounded perspective with clear pedestal anchoring
        camera.position.set(0, 0.88, 2.48);
        camera.lookAt(0, 0.68, 0);
      }`
);

// 4. Update moraModel position when loaded (flush to ground)
html = html.replace(
  'moraModel.position.set(0, 0, 0);',
  'moraModel.position.set(0, -0.002, 0);'
);

// 5. Update MoraCharacterController.update with full AI Body reactions:
const oldUpdateStart = /update\(dt, time, state, audioLevel, activeReaction, reactionTimer\) \{[\s\S]*?\/\/ 1\. Skeletal Motion Blending[\s\S]*?if \(this\.hasClips\) \{[\s\S]*?this\.fadeToAction\(desired, 0\.35\);[\s\S]*?this\.mixer\.update\(dt\);[\s\S]*?\}/;

const newUpdateStart = `update(dt, time, state, audioLevel, activeReaction, reactionTimer) {
        // 0. Enforce Physical Grounding Lock (Strictly zero vertical drifting)
        if (this.root) {
          this.root.position.set(0, -0.002, 0);
        }

        // 1. Skeletal Motion Blending (Jarvis AI Physical Body State Machine)
        if (this.hasClips) {
          let desired = "idle";
          if (activeReaction === "dance" || activeReaction === "rumba") {
            desired = "dance";
          } else if (activeReaction === "joy" || activeReaction === "laugh" || activeReaction === "success") {
            desired = "joy";
          } else if (activeReaction === "wave") {
            desired = "wave";
          } else if (activeReaction === "teach" || activeReaction === "learn" || activeReaction === "nod" || activeReaction === "executing") {
            desired = "learn";
          } else if (activeReaction === "curious" || activeReaction === "thinking" || activeReaction === "failed") {
            desired = "thinking";
          } else if (activeReaction === "surprise" || activeReaction === "poke") {
            desired = "learn";
          } else if (activeReaction === "cry") {
            desired = "crying";
          } else if (state === "speaking") {
            desired = "talking";
          } else if (state === "thinking") {
            desired = "thinking";
          } else if (state === "listening") {
            // Attentive listening posture (uses grounded idle with alert gaze kinematics)
            desired = "idle";
          } else {
            desired = "idle";
          }

          this.fadeToAction(desired, 0.28);
          this.mixer.update(dt);
        }`;

html = html.replace(oldUpdateStart, newUpdateStart);

// 6. Update Facial Morph logic to include "success", "executing", "listening":
html = html.replace(
  'if (activeReaction === "joy" || activeReaction === "laugh") {',
  'if (activeReaction === "joy" || activeReaction === "laugh" || activeReaction === "success") {'
);

html = html.replace(
  'else if (activeReaction === "teach" || activeReaction === "learn" || activeReaction === "nod") {',
  'else if (activeReaction === "teach" || activeReaction === "learn" || activeReaction === "nod" || activeReaction === "executing") {'
);

// 7. Add listening facial expression in morphs:
if (!html.includes('else if (state === "listening") {')) {
  html = html.replace(
    '// Natural eyelid blinking: 110ms duration every 3.2s',
    `// Attentive listening facial reaction
        if (state === "listening") {
          targetBrwJoy = 0.40;
          targetEyeSurprised = 0.35;
          targetMthSmall = 0.15;
        }

        // Natural eyelid blinking: 110ms duration every 3.2s`
  );
}

// 8. Update triggerMoraReaction timers:
html = html.replace(
  /function triggerMoraReaction\(reaction\) \{[\s\S]*?console\.log\("Mora reaction triggered:", activeReaction, "timer:", reactionTimer\);\s*\}/,
  `function triggerMoraReaction(reaction) {
      activeReaction = (reaction || "").toLowerCase();
      if (activeReaction === "dance") reactionTimer = 7.5;
      else if (activeReaction === "joy" || activeReaction === "laugh" || activeReaction === "success") reactionTimer = 5.0;
      else if (activeReaction === "wave") reactionTimer = 4.1;
      else if (activeReaction === "teach" || activeReaction === "learn" || activeReaction === "executing") reactionTimer = 3.5;
      else if (activeReaction === "failed" || activeReaction === "curious" || activeReaction === "thinking") reactionTimer = 3.0;
      else if (activeReaction === "surprise" || activeReaction === "poke") reactionTimer = 2.8;
      else reactionTimer = 2.5;
      console.log("Mora reaction triggered:", activeReaction, "timer:", reactionTimer);
    }`
);

fs.writeFileSync(moraHtmlPath, html, 'utf8');
console.log('mora_character.html successfully updated with grounded platform and AI body reactions!');
