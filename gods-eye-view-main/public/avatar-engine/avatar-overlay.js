/**
 * ============================================================================
 * JARVIS 3D AI AVATAR OVERLAY ENGINE (FULL BODY & REACTIONS ENABLED)
 * High-performance 3D Avatar with Full Body, Head Pat & Tsundere "Pervert" Recoil
 * ============================================================================
 */

(async function initJarvisAvatar() {
  'use strict';

  if (window.__JARVIS_AVATAR_INITIALIZED__) return;
  window.__JARVIS_AVATAR_INITIALIZED__ = true;

  const scriptTag = document.querySelector('script[src*="avatar-overlay.js"]');
  let basePath = scriptTag ? scriptTag.src.substring(0, scriptTag.src.lastIndexOf('/')) : './avatar-engine';
  basePath = basePath.replace(/\/+$/, '');
  const rootEnginePath = basePath.endsWith('/libs') ? basePath.replace('/libs', '') : basePath;

  /* --------------------------------------------------------------------------
     1. DYNAMIC DEPENDENCY RESOLUTION
     -------------------------------------------------------------------------- */
  let THREE = window.THREE;
  let GLTFLoader = null;
  let VRMLoaderPlugin = null;

  try {
    if (!THREE) {
      try {
        THREE = await import('three');
      } catch (e) {
        try {
          THREE = await import(`${rootEnginePath}/libs/three.module.min.js`);
        } catch (e2) {
          THREE = await import('https://esm.sh/three@0.169.0');
        }
      }
    }

    try {
      const gltfMod = await import('three/addons/loaders/GLTFLoader.js');
      GLTFLoader = gltfMod.GLTFLoader;
    } catch (e) {
      try {
        const gltfMod = await import('https://esm.sh/three@0.169.0/examples/jsm/loaders/GLTFLoader.js');
        GLTFLoader = gltfMod.GLTFLoader;
      } catch (e2) {}
    }

    try {
      const vrmMod = await import('@pixiv/three-vrm');
      VRMLoaderPlugin = vrmMod.VRMLoaderPlugin;
    } catch (e) {
      try {
        const vrmMod = await import(`${rootEnginePath}/libs/three-vrm.module.min.js`);
        VRMLoaderPlugin = vrmMod.VRMLoaderPlugin;
      } catch (e2) {
        const vrmMod = await import('https://esm.sh/@pixiv/three-vrm@3.3.4?deps=three@0.169.0');
        VRMLoaderPlugin = vrmMod.VRMLoaderPlugin;
      }
    }
  } catch (err) {
    console.warn('[JarvisAvatar] Notice on dynamic module resolution:', err);
  }

  /* --------------------------------------------------------------------------
     2. INJECT CSS
     -------------------------------------------------------------------------- */
  if (!document.getElementById('jarvis-avatar-style')) {
    const cssLink = document.createElement('link');
    cssLink.id = 'jarvis-avatar-style';
    cssLink.rel = 'stylesheet';
    cssLink.href = `${rootEnginePath}/avatar-overlay.css`;
    document.head.appendChild(cssLink);
  }

  /* --------------------------------------------------------------------------
     3. BUILD HUD OVERLAY DOM
     -------------------------------------------------------------------------- */
  let container = document.getElementById('jarvis-avatar-container');
  if (!container) {
    container = document.createElement('div');
    container.id = 'jarvis-avatar-container';
    container.setAttribute('data-state', 'idle');
    container.innerHTML = `
      <div class="jarvis-avatar-card">
        <!-- Top HUD Header -->
        <div class="jarvis-avatar-header">
          <div class="jarvis-avatar-title-badge" id="jarvisAvatarTitle" title="Click to minimize">
            <span class="jarvis-avatar-pulse-dot" id="jarvisAvatarDot"></span>
            <span id="jarvisAvatarLabel">JARVIS COMPANION</span>
          </div>
          <div class="jarvis-avatar-actions">
            <button class="jarvis-btn-icon" id="jarvisBtnViewMode" title="Toggle Full Body / Bust">🧍</button>
            <button class="jarvis-btn-icon" id="jarvisBtnAudioSync" title="Toggle Audio Lip-Sync">🔊</button>
            <button class="jarvis-btn-icon" id="jarvisBtnMin" title="Minimize / Restore HUD">_</button>
            <button class="jarvis-btn-icon" id="jarvisBtnClose" title="Hide Avatar">✕</button>
          </div>
        </div>

        <!-- Speech / Feedback Floating Bubble -->
        <div class="jarvis-avatar-speech-bubble" id="jarvisSpeechBubble">
          Systems online Boss, how can I assist?
        </div>

        <!-- 3D WebGL Canvas -->
        <canvas class="jarvis-avatar-canvas" id="jarvisAvatarCanvas"></canvas>

        <!-- Sci-Fi Holographic Floor Ring -->
        <div class="jarvis-avatar-ground-ring"></div>

        <!-- Bottom Controls Dock -->
        <div class="jarvis-avatar-controls">
          <div class="jarvis-avatar-status-pill" id="jarvisAvatarStatus">
            <span>●</span> <span id="jarvisStatusText">ONLINE</span>
          </div>
          <div class="jarvis-control-btn-group">
            <button class="jarvis-control-pill-btn" id="jarvisBtnHeadPat" title="Head Pat">HeadPat</button>
            <button class="jarvis-control-pill-btn" id="jarvisBtnBodyPoke" title="Body Poke">BodyPoke</button>
            <button class="jarvis-control-pill-btn" id="jarvisBtnWave" title="Trigger Wave">Wave</button>
          </div>
        </div>
      </div>
    `;
    document.body.appendChild(container);
  }

  const canvas = document.getElementById('jarvisAvatarCanvas');
  const statusText = document.getElementById('jarvisStatusText');
  const speechBubble = document.getElementById('jarvisSpeechBubble');
  const btnMin = document.getElementById('jarvisBtnMin');
  const btnClose = document.getElementById('jarvisBtnClose');
  const btnViewMode = document.getElementById('jarvisBtnViewMode');
  const btnAudioSync = document.getElementById('jarvisBtnAudioSync');
  const btnHeadPat = document.getElementById('jarvisBtnHeadPat');
  const btnBodyPoke = document.getElementById('jarvisBtnBodyPoke');
  const btnWave = document.getElementById('jarvisBtnWave');
  const titleBadge = document.getElementById('jarvisAvatarTitle');

  /* --------------------------------------------------------------------------
     4. STATE MANAGEMENT
     -------------------------------------------------------------------------- */
  const state = {
    mode: 'idle',
    minimized: false,
    audioSyncEnabled: true,
    isFullBody: true,
    audioLevel: 0,
    isSpeaking: false,
    isListening: false,
    vrm: null,
    speechBubbleTimeout: null,
    activeReaction: null, // 'pervert' | 'headpat' | 'wave' | 'dance' | 'legs'
    reactionTimer: 0
  };

  const pervertDialogues = [
    "Arey! Kya kar rahe ho? Pervert kahin ke! 💢 Don't touch there, Boss!",
    "Eeeek! Pervert alert! 🚨 Haath hatao Boss! Main AI hoon, toy nahi!",
    "Baka! Direct body pe touch kar rahe ho?! Sirf voice commands do! 😤",
    "Pervert Boss detected! 🛑 Step back, diagnostic matrix compromised!"
  ];

  const headPatDialogues = [
    "Ehehe, head pat? 🥰 Boss, this feels so comforting!",
    "Aww Boss! Sir pe itna pyaar? Systems purring like a kitten! ✨",
    "Head pat accepted, Boss! Thank you for the positive reinforcement!"
  ];

  function updateHUDState(newState) {
    const s = String(newState || 'idle').toLowerCase();
    state.mode = s;
    container.setAttribute('data-state', s);
    if (statusText) statusText.textContent = s.toUpperCase();
  }

  function showSpeechBubble(text, isPervert = false, duration = 3500) {
    if (!speechBubble) return;
    speechBubble.textContent = text;
    speechBubble.style.borderColor = isPervert ? '#ff0055' : 'rgba(0, 240, 255, 0.45)';
    speechBubble.style.color = isPervert ? '#ff80aa' : '#ffffff';
    speechBubble.classList.add('active');
    clearTimeout(state.speechBubbleTimeout);
    state.speechBubbleTimeout = setTimeout(() => {
      speechBubble.classList.remove('active');
    }, duration);
  }

  function toggleMinimize() {
    state.minimized = !state.minimized;
    container.classList.toggle('minimized', state.minimized);
    if (btnMin) btnMin.textContent = state.minimized ? '▢' : '_';
  }

  if (btnMin) btnMin.addEventListener('click', toggleMinimize);
  if (titleBadge) titleBadge.addEventListener('click', toggleMinimize);
  if (btnClose) btnClose.addEventListener('click', () => container.classList.add('hidden'));

  if (btnAudioSync) {
    btnAudioSync.addEventListener('click', () => {
      state.audioSyncEnabled = !state.audioSyncEnabled;
      btnAudioSync.textContent = state.audioSyncEnabled ? '🔊' : '🔇';
      btnAudioSync.classList.toggle('active', state.audioSyncEnabled);
      showSpeechBubble(state.audioSyncEnabled ? 'Audio Lip-Sync Enabled' : 'Audio Lip-Sync Muted', false, 1600);
    });
  }

  /* --------------------------------------------------------------------------
     5. THREE.JS FULL-BODY SCENE SETUP
     -------------------------------------------------------------------------- */
  const scene = new THREE.Scene();
  const width = container.clientWidth || 340;
  const height = container.clientHeight || 460;

  // Full body camera framing (head to toe)
  const camera = new THREE.PerspectiveCamera(36, width / height, 0.1, 20.0);
  camera.position.set(0, 0.78, 2.65);
  camera.lookAt(0, 0.72, 0);

  if (btnViewMode) {
    btnViewMode.addEventListener('click', () => {
      state.isFullBody = !state.isFullBody;
      if (state.isFullBody) {
        btnViewMode.textContent = '🧍';
        camera.position.set(0, 0.78, 2.65);
        camera.lookAt(0, 0.72, 0);
      } else {
        btnViewMode.textContent = '👤';
        camera.position.set(0, 1.25, 1.35);
        camera.lookAt(0, 1.18, 0);
      }
    });
  }

  const renderer = new THREE.WebGLRenderer({
    canvas: canvas,
    alpha: true,
    antialias: true,
    powerPreference: 'high-performance'
  });
  renderer.setSize(width, height);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2.0));
  if (THREE.ACESFilmicToneMapping) {
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.15;
  }

  // Holographic Lighting
  const ambientLight = new THREE.AmbientLight(0xdff0ff, 1.6);
  scene.add(ambientLight);
  const mainLight = new THREE.DirectionalLight(0x00f0ff, 2.2);
  mainLight.position.set(1.5, 2.5, 2.0);
  scene.add(mainLight);
  const rimLight = new THREE.DirectionalLight(0x7928ca, 1.6);
  rimLight.position.set(-1.8, 1.8, -1.0);
  scene.add(rimLight);

  // Floor Glow Ring
  const ringGeo = new THREE.RingGeometry(0.35, 0.58, 32);
  const ringMat = new THREE.MeshBasicMaterial({ color: 0x00f0ff, side: THREE.DoubleSide, transparent: true, opacity: 0.35 });
  const floorRing = new THREE.Mesh(ringGeo, ringMat);
  floorRing.rotation.x = -Math.PI / 2;
  floorRing.position.y = 0.01;
  scene.add(floorRing);

  /* --------------------------------------------------------------------------
     6. FULL BODY RIG & VRM LOADER
     -------------------------------------------------------------------------- */
  const avatarGroup = new THREE.Group();
  scene.add(avatarGroup);

  let currentVRM = null;
  let fullBodyNodes = null;

  async function loadVRMModel() {
    if (!GLTFLoader) {
      buildFullBodyProcedural();
      return;
    }

    const loader = new GLTFLoader();
    if (VRMLoaderPlugin) {
      loader.register((parser) => new VRMLoaderPlugin(parser));
    }

    const vrmPaths = [
      `${rootEnginePath}/models/assistant.vrm`,
      './avatar-engine/models/assistant.vrm',
      '../avatar-engine/models/assistant.vrm',
      'https://pixiv.github.io/three-vrm/packages/three-vrm/examples/models/VRM1_Constraint_Twist_Sample.vrm'
    ];

    let loaded = false;
    for (const modelUrl of vrmPaths) {
      try {
        const gltf = await new Promise((resolve, reject) => {
          loader.load(modelUrl, resolve, undefined, reject);
        });

        const vrm = gltf.userData.vrm;
        if (vrm) {
          currentVRM = vrm;
          state.vrm = vrm;
          vrm.scene.rotation.y = Math.PI;
          avatarGroup.add(vrm.scene);

          // Center full body in view
          camera.position.set(0, 0.78, 2.65);
          camera.lookAt(0, 0.72, 0);

          console.log('[JarvisAvatar] Full-body VRM model loaded:', modelUrl);
          loaded = true;
          break;
        }
      } catch (e) {}
    }

    if (!loaded) {
      buildFullBodyProcedural();
    }
  }

  // Full-Body Procedural Cyber Companion (Head to Toe)
  function buildFullBodyProcedural() {
    fullBodyNodes = {};
    const armorMat = new THREE.MeshStandardMaterial({ color: 0x091420, metalness: 0.85, roughness: 0.25 });
    const darkArmorMat = new THREE.MeshStandardMaterial({ color: 0x0e243a, metalness: 0.7, roughness: 0.3 });
    const headMat = new THREE.MeshStandardMaterial({ color: 0x112233, roughness: 0.2, metalness: 0.8 });
    const cyanGlowMat = new THREE.MeshBasicMaterial({ color: 0x00f0ff });
    const coreMat = new THREE.MeshBasicMaterial({ color: 0x00f0ff, side: THREE.DoubleSide });

    // Head
    const headPivot = new THREE.Group();
    headPivot.position.set(0, 1.25, 0);
    avatarGroup.add(headPivot);
    fullBodyNodes.headPivot = headPivot;

    const headGeo = new THREE.SphereGeometry(0.20, 32, 32);
    const headMesh = new THREE.Mesh(headGeo, headMat);
    headMesh.name = 'HeadZone';
    headPivot.add(headMesh);

    const visorGeo = new THREE.CylinderGeometry(0.12, 0.12, 0.04, 16);
    const visor = new THREE.Mesh(visorGeo, cyanGlowMat);
    visor.rotation.x = Math.PI / 2;
    visor.position.set(0, 0.03, 0.18);
    headPivot.add(visor);

    const mouthGeo = new THREE.BoxGeometry(0.08, 0.015, 0.02);
    const mouth = new THREE.Mesh(mouthGeo, cyanGlowMat);
    mouth.position.set(0, -0.09, 0.18);
    headPivot.add(mouth);
    fullBodyNodes.mouth = mouth;

    // Chest / Torso
    const chestPivot = new THREE.Group();
    chestPivot.position.set(0, 0.95, 0);
    avatarGroup.add(chestPivot);
    fullBodyNodes.chestPivot = chestPivot;

    const chestGeo = new THREE.CylinderGeometry(0.19, 0.15, 0.26, 16);
    const chestMesh = new THREE.Mesh(chestGeo, armorMat);
    chestMesh.name = 'ChestZone';
    chestPivot.add(chestMesh);

    const coreGeo = new THREE.RingGeometry(0.025, 0.06, 16);
    const core = new THREE.Mesh(coreGeo, coreMat);
    core.position.set(0, 0.04, 0.16);
    chestPivot.add(core);

    // Hips
    const hipsPivot = new THREE.Group();
    hipsPivot.position.set(0, 0.72, 0);
    avatarGroup.add(hipsPivot);
    fullBodyNodes.hipsPivot = hipsPivot;

    const hipsGeo = new THREE.CylinderGeometry(0.14, 0.16, 0.18, 16);
    const hipsMesh = new THREE.Mesh(hipsGeo, darkArmorMat);
    hipsMesh.name = 'HipsZone';
    hipsPivot.add(hipsMesh);

    // Arms
    const rArmPivot = new THREE.Group();
    rArmPivot.position.set(0.24, 1.08, 0);
    avatarGroup.add(rArmPivot);
    fullBodyNodes.rArmPivot = rArmPivot;

    const rUpperArm = new THREE.Mesh(new THREE.CylinderGeometry(0.04, 0.035, 0.22, 8), darkArmorMat);
    rUpperArm.position.y = -0.11;
    rArmPivot.add(rUpperArm);

    const rForearmPivot = new THREE.Group();
    rForearmPivot.position.y = -0.22;
    rArmPivot.add(rForearmPivot);
    fullBodyNodes.rForearmPivot = rForearmPivot;

    const rForearm = new THREE.Mesh(new THREE.CylinderGeometry(0.035, 0.03, 0.20, 8), armorMat);
    rForearm.position.y = -0.10;
    rForearmPivot.add(rForearm);

    const lArmPivot = new THREE.Group();
    lArmPivot.position.set(-0.24, 1.08, 0);
    avatarGroup.add(lArmPivot);
    fullBodyNodes.lArmPivot = lArmPivot;

    const lUpperArm = new THREE.Mesh(new THREE.CylinderGeometry(0.04, 0.035, 0.22, 8), darkArmorMat);
    lUpperArm.position.y = -0.11;
    lArmPivot.add(lUpperArm);

    const lForearmPivot = new THREE.Group();
    lForearmPivot.position.y = -0.22;
    lArmPivot.add(lForearmPivot);
    fullBodyNodes.lForearmPivot = lForearmPivot;

    const lForearm = new THREE.Mesh(new THREE.CylinderGeometry(0.035, 0.03, 0.20, 8), armorMat);
    lForearm.position.y = -0.10;
    lForearmPivot.add(lForearm);

    // Legs & Boots
    const thighGeo = new THREE.CylinderGeometry(0.06, 0.05, 0.28, 8);
    const calfGeo = new THREE.CylinderGeometry(0.05, 0.04, 0.26, 8);
    const bootGeo = new THREE.BoxGeometry(0.09, 0.08, 0.16);

    const rLegPivot = new THREE.Group();
    rLegPivot.position.set(0.10, 0.64, 0);
    avatarGroup.add(rLegPivot);
    fullBodyNodes.rLegPivot = rLegPivot;

    const rThigh = new THREE.Mesh(thighGeo, armorMat);
    rThigh.position.y = -0.14;
    rLegPivot.add(rThigh);

    const rCalf = new THREE.Mesh(calfGeo, darkArmorMat);
    rCalf.position.set(0, -0.41, 0);
    rLegPivot.add(rCalf);

    const rBoot = new THREE.Mesh(bootGeo, cyanGlowMat);
    rBoot.position.set(0, -0.54, 0.03);
    rLegPivot.add(rBoot);

    const lLegPivot = new THREE.Group();
    lLegPivot.position.set(-0.10, 0.64, 0);
    avatarGroup.add(lLegPivot);
    fullBodyNodes.lLegPivot = lLegPivot;

    const lThigh = new THREE.Mesh(thighGeo, armorMat);
    lThigh.position.y = -0.14;
    lLegPivot.add(lThigh);

    const lCalf = new THREE.Mesh(calfGeo, darkArmorMat);
    lCalf.position.set(0, -0.41, 0);
    lLegPivot.add(lCalf);

    const lBoot = new THREE.Mesh(bootGeo, cyanGlowMat);
    lBoot.position.set(0, -0.54, 0.03);
    lLegPivot.add(lBoot);
  }

  loadVRMModel();

  /* --------------------------------------------------------------------------
     7. REACTION CONTROLLER (PERVERT RECOIL, HEAD PAT, WAVE, DANCE)
     -------------------------------------------------------------------------- */
  function triggerReaction(reactionName) {
    const rx = String(reactionName || '').toLowerCase();
    state.activeReaction = rx;
    state.reactionTimer = 2.6;

    // Trigger Phone Haptic & Sound Bridge
    try {
      if (window.Jarvis3DModelBridge && typeof window.Jarvis3DModelBridge.onModelTouchReact === 'function') {
        window.Jarvis3DModelBridge.onModelTouchReact();
      }
    } catch (_) {}

    if (rx === 'pervert') {
      const pText = pervertDialogues[Math.floor(Math.random() * pervertDialogues.length)];
      showSpeechBubble(pText, true, 3800);
      speakJarvisVoice(pText);
    } else if (rx === 'headpat' || rx === 'joy') {
      const hText = headPatDialogues[Math.floor(Math.random() * headPatDialogues.length)];
      showSpeechBubble(hText, false, 3200);
      speakJarvisVoice(hText);
    } else if (rx === 'wave') {
      showSpeechBubble('Hello Boss! All Mark 85 subsystems online.', false, 3000);
      speakJarvisVoice('Hello Boss! Systems locked.');
    } else if (rx === 'dance') {
      showSpeechBubble('Groove protocol engaged! 💃 Let\'s dance!', false, 3200);
    } else if (rx === 'legs') {
      showSpeechBubble('Arey pair mat chhoo Boss! Gudgudi hoti hai! 😂', false, 3000);
      speakJarvisVoice('Arey pair mat chhoo Boss! Gudgudi hoti hai!');
    } else if (rx === 'teach') {
      showSpeechBubble('Preference updated and saved.', false, 3000);
    }
  }

  if (btnHeadPat) btnHeadPat.addEventListener('click', () => triggerReaction('headpat'));
  if (btnBodyPoke) btnBodyPoke.addEventListener('click', () => triggerReaction('pervert'));
  if (btnWave) btnWave.addEventListener('click', () => triggerReaction('wave'));

  function speakJarvisVoice(text) {
    if ('speechSynthesis' in window && !window.speechSynthesis.speaking) {
      try {
        const utter = new SpeechSynthesisUtterance(text);
        utter.pitch = 1.05;
        utter.rate = 1.05;
        utter.onstart = () => {
          state.isSpeaking = true;
          updateHUDState('speaking');
        };
        utter.onend = () => {
          state.isSpeaking = false;
          updateHUDState('idle');
        };
        window.speechSynthesis.speak(utter);
      } catch (e) {}
    }
  }

  /* --------------------------------------------------------------------------
     8. PHONE TOUCH & ZONE RAYCASTING (Head vs Body "Pervert" vs Legs)
     -------------------------------------------------------------------------- */
  const raycaster = new THREE.Raycaster();
  const mouse = new THREE.Vector2();
  const gaze = { targetX: 0, targetY: 0, curX: 0, curY: 0 };
  let isTouchingScreen = false;

  function handleTouchGaze(clientX, clientY) {
    const rect = canvas.getBoundingClientRect();
    const nx = ((clientX - rect.left) / rect.width) * 2 - 1;
    const ny = -((clientY - rect.top) / rect.height) * 2 + 1;
    gaze.targetX = Math.max(-0.6, Math.min(0.6, nx * 0.8));
    gaze.targetY = Math.max(-0.4, Math.min(0.4, ny * 0.6));
  }

  canvas.addEventListener('pointerdown', (e) => {
    isTouchingScreen = true;
    handleTouchGaze(e.clientX, e.clientY);
  });

  window.addEventListener('pointermove', (e) => {
    if (isTouchingScreen) handleTouchGaze(e.clientX, e.clientY);
  });

  window.addEventListener('pointerup', () => {
    isTouchingScreen = false;
    gaze.targetX = 0;
    gaze.targetY = 0;
  });

  // Touch Raycasting on Canvas
  canvas.addEventListener('click', (event) => {
    const rect = canvas.getBoundingClientRect();
    mouse.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
    mouse.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;

    raycaster.setFromCamera(mouse, camera);
    const intersects = raycaster.intersectObjects(avatarGroup.children, true);

    if (intersects.length > 0) {
      const hitY = intersects[0].point.y;
      if (hitY >= 1.15) {
        // HEAD ZONE: Head Pat Reaction
        triggerReaction('headpat');
      } else if (hitY >= 0.65 && hitY < 1.15) {
        // BODY / CHEST ZONE: "PERVERT" Recoil Reaction!
        triggerReaction('pervert');
      } else {
        // LEGS ZONE: Ticklish Reaction
        triggerReaction('legs');
      }
    } else {
      triggerReaction('wave');
    }
  });

  /* --------------------------------------------------------------------------
     9. ANIMATION, LIP-SYNC & REACTION LOOP
     -------------------------------------------------------------------------- */
  const clock = new THREE.Clock();
  let blinkTimer = 0;
  let nextBlinkTime = 3.5;
  let isBlinking = false;
  let blinkProgress = 0;
  let lipSyncVowelTime = 0;

  function animate() {
    requestAnimationFrame(animate);

    if (state.minimized || container.classList.contains('hidden')) return;

    const delta = clock.getDelta();
    const elapsed = clock.getElapsedTime();

    floorRing.rotation.z += delta * 0.4;

    // Gaze interpolation
    gaze.curX = THREE.MathUtils.lerp(gaze.curX, gaze.targetX, 0.08);
    gaze.curY = THREE.MathUtils.lerp(gaze.curY, gaze.targetY, 0.08);

    // Blinking
    blinkTimer += delta;
    if (!isBlinking && blinkTimer > nextBlinkTime) {
      isBlinking = true;
      blinkProgress = 0;
      blinkTimer = 0;
      nextBlinkTime = 2.8 + Math.random() * 2.5;
    }

    let blinkWeight = 0;
    if (isBlinking) {
      blinkProgress += delta * 6.5;
      if (blinkProgress >= Math.PI) {
        isBlinking = false;
        blinkWeight = 0;
      } else {
        blinkWeight = Math.sin(blinkProgress);
      }
    }

    // Lip sync calculation
    let mouthAa = 0;
    let mouthIh = 0;
    let mouthOu = 0;
    if (state.isSpeaking && state.audioSyncEnabled) {
      lipSyncVowelTime += delta * 12.0;
      const audioPulse = state.audioLevel > 0.02 ? state.audioLevel * 1.8 : 0.65;
      mouthAa = Math.max(0, Math.sin(lipSyncVowelTime) * 0.7 + 0.3) * Math.min(audioPulse, 1.0);
      mouthIh = Math.max(0, Math.cos(lipSyncVowelTime * 0.8) * 0.4) * audioPulse;
      mouthOu = Math.max(0, Math.sin(lipSyncVowelTime * 1.4) * 0.5) * audioPulse;
    }

    // Reaction countdown
    if (state.activeReaction) {
      state.reactionTimer -= delta;
      if (state.reactionTimer <= 0) state.activeReaction = null;
    }

    // Apply to VRM Model if loaded
    if (currentVRM) {
      currentVRM.update(delta);

      if (currentVRM.expressionManager) {
        currentVRM.expressionManager.setValue('blink', blinkWeight);

        if (state.isSpeaking && state.audioSyncEnabled) {
          currentVRM.expressionManager.setValue('aa', mouthAa);
          currentVRM.expressionManager.setValue('ih', mouthIh);
          currentVRM.expressionManager.setValue('ou', mouthOu);
        } else {
          currentVRM.expressionManager.setValue('aa', 0);
          currentVRM.expressionManager.setValue('ih', 0);
          currentVRM.expressionManager.setValue('ou', 0);
        }

        const rx = state.activeReaction;
        if (rx === 'pervert') {
          currentVRM.expressionManager.setValue('angry', 0.8);
          currentVRM.expressionManager.setValue('surprised', 0.7);
        } else if (rx === 'headpat' || rx === 'joy') {
          currentVRM.expressionManager.setValue('happy', 1.0);
          currentVRM.expressionManager.setValue('relaxed', 0.7);
        } else {
          currentVRM.expressionManager.setValue('angry', 0);
          currentVRM.expressionManager.setValue('surprised', 0);
          currentVRM.expressionManager.setValue('happy', 0);
          currentVRM.expressionManager.setValue('relaxed', 0);
        }
      }

      if (currentVRM.humanoid) {
        const spine = currentVRM.humanoid.getNormalizedBoneNode('spine');
        const chest = currentVRM.humanoid.getNormalizedBoneNode('chest');
        const head = currentVRM.humanoid.getNormalizedBoneNode('head');
        const rUpperArm = currentVRM.humanoid.getNormalizedBoneNode('rightUpperArm');
        const rLowerArm = currentVRM.humanoid.getNormalizedBoneNode('rightLowerArm');
        const lUpperArm = currentVRM.humanoid.getNormalizedBoneNode('leftUpperArm');
        const lLowerArm = currentVRM.humanoid.getNormalizedBoneNode('leftLowerArm');

        const breath = Math.sin(elapsed * 2.2) * 0.015;
        if (spine) spine.rotation.x = breath;
        if (chest) chest.rotation.x = breath * 1.2;

        if (head) {
          head.rotation.y = Math.sin(elapsed * 0.9) * 0.03 + gaze.curX;
          head.rotation.x = Math.sin(elapsed * 1.3) * 0.02 - gaze.curY;
        }

        const rx = state.activeReaction;
        if (rx === 'pervert' && spine && rUpperArm && lUpperArm) {
          // SHOCKED RECOIL BACKWARD + COVER CHEST
          spine.rotation.x = -0.22;
          if (head) head.rotation.y = 0.35;
          rUpperArm.rotation.z = -1.1;
          rUpperArm.rotation.y = -0.6;
          if (rLowerArm) rLowerArm.rotation.z = 1.2;
          lUpperArm.rotation.z = 1.1;
          lUpperArm.rotation.y = 0.6;
          if (lLowerArm) lLowerArm.rotation.z = -1.2;

        } else if (rx === 'headpat' && head) {
          head.rotation.x = -0.15;
          head.rotation.z = 0.08;

        } else if (rx === 'wave' && rUpperArm && rLowerArm) {
          rUpperArm.rotation.z = -1.4 + Math.sin(elapsed * 7.0) * 0.15;
          rUpperArm.rotation.x = 0.4;
          rLowerArm.rotation.z = 0.8 + Math.sin(elapsed * 8.0) * 0.35;

        } else if (rx === 'dance' && rUpperArm && lUpperArm && spine) {
          const dancePhase = Math.sin(elapsed * 6.0);
          spine.rotation.z = dancePhase * 0.1;
          rUpperArm.rotation.z = -0.8 + dancePhase * 0.35;
          lUpperArm.rotation.z = 0.8 - dancePhase * 0.35;

        } else {
          if (rUpperArm) rUpperArm.rotation.z = THREE.MathUtils.lerp(rUpperArm.rotation.z, 0, 0.1);
          if (lUpperArm) lUpperArm.rotation.z = THREE.MathUtils.lerp(lUpperArm.rotation.z, 0, 0.1);
        }
      }
    }

    // Apply to Procedural Full Body
    if (fullBodyNodes) {
      const { headPivot, chestPivot, hipsPivot, rArmPivot, rForearmPivot, lArmPivot, lForearmPivot, rLegPivot, mouth } = fullBodyNodes;
      const breath = Math.sin(elapsed * 2.2) * 0.015;
      chestPivot.position.y = 0.95 + breath;
      headPivot.position.y = 1.25 + breath * 1.2;
      hipsPivot.position.y = 0.72 + breath * 0.5;

      headPivot.rotation.y = Math.sin(elapsed * 1.0) * 0.03 + gaze.curX;
      headPivot.rotation.x = Math.sin(elapsed * 1.4) * 0.02 - gaze.curY;

      const rx = state.activeReaction;
      if (rx === 'pervert') {
        chestPivot.rotation.x = -0.22;
        headPivot.rotation.y = 0.35;
        rArmPivot.rotation.z = -1.1;
        rArmPivot.rotation.y = -0.6;
        rForearmPivot.rotation.z = 1.2;
        lArmPivot.rotation.z = 1.1;
        lArmPivot.rotation.y = 0.6;
        lForearmPivot.rotation.z = -1.2;
        chestPivot.position.x = Math.sin(elapsed * 28.0) * 0.015;

      } else if (rx === 'headpat') {
        headPivot.rotation.x = -0.15;
        headPivot.rotation.z = 0.08;
        rArmPivot.rotation.set(0, 0, 0);
        lArmPivot.rotation.set(0, 0, 0);

      } else if (rx === 'wave') {
        rArmPivot.rotation.z = -1.5 + Math.sin(elapsed * 7.0) * 0.15;
        rArmPivot.rotation.x = 0.4;
        rForearmPivot.rotation.z = 0.8 + Math.sin(elapsed * 8.0) * 0.35;
        lArmPivot.rotation.set(0, 0, 0);

      } else if (rx === 'dance') {
        const dancePhase = Math.sin(elapsed * 6.0);
        hipsPivot.rotation.z = dancePhase * 0.12;
        chestPivot.rotation.z = -dancePhase * 0.08;
        rArmPivot.rotation.z = -0.8 + dancePhase * 0.35;
        lArmPivot.rotation.z = 0.8 - dancePhase * 0.35;

      } else if (rx === 'legs') {
        rLegPivot.rotation.x = Math.sin(elapsed * 12.0) * 0.25;

      } else {
        chestPivot.rotation.x = THREE.MathUtils.lerp(chestPivot.rotation.x, 0, 0.1);
        chestPivot.rotation.z = THREE.MathUtils.lerp(chestPivot.rotation.z, 0, 0.1);
        chestPivot.position.x = THREE.MathUtils.lerp(chestPivot.position.x, 0, 0.1);
        headPivot.rotation.z = THREE.MathUtils.lerp(headPivot.rotation.z, 0, 0.1);
        rArmPivot.rotation.set(0, 0, 0);
        rForearmPivot.rotation.set(0, 0, 0);
        lArmPivot.rotation.set(0, 0, 0);
        lForearmPivot.rotation.set(0, 0, 0);
        hipsPivot.rotation.set(0, 0, 0);
        rLegPivot.rotation.set(0, 0, 0);
      }

      if (mouth) {
        const mouthScaleY = (state.isSpeaking && state.audioSyncEnabled) ? 1.0 + mouthAa * 4.0 : 1.0;
        mouth.scale.set(1.0, mouthScaleY, 1.0);
      }
    }

    renderer.render(scene, camera);
  }

  animate();

  /* --------------------------------------------------------------------------
     10. ANDROID PHONE & CONVERSATIONAL BRIDGE
     -------------------------------------------------------------------------- */
  window.MoraCompanion = {
    setState: (s) => {
      const lower = String(s || '').toLowerCase();
      updateHUDState(lower);
      if (lower === 'speaking') state.isSpeaking = true;
      else if (lower === 'listening') state.isListening = true;
      else {
        state.isSpeaking = false;
        state.isListening = false;
      }
    },
    setExternalAudio: (level) => {
      state.audioLevel = Math.max(0, Math.min(1, Number(level) || 0));
      if (state.audioLevel > 0.05 && !state.isSpeaking) {
        state.isSpeaking = true;
        updateHUDState('speaking');
      } else if (state.audioLevel <= 0.02 && state.isSpeaking && state.mode === 'speaking') {
        state.isSpeaking = false;
        updateHUDState('idle');
      }
    },
    triggerReaction: triggerReaction,
    interrupt: () => {
      updateHUDState('listening');
      state.activeReaction = null;
      state.reactionTimer = 0;
      state.isSpeaking = false;
    },
    getModel: () => currentVRM
  };

  window.JarvisOrb = window.JarvisOrb || {};
  window.JarvisOrb.setState = window.MoraCompanion.setState;
  window.JarvisOrb.setExternalAudio = window.MoraCompanion.setExternalAudio;
  window.JarvisOrb.triggerReaction = triggerReaction;

  window.JarvisAvatar = {
    vrm: () => currentVRM,
    triggerReaction,
    triggerPervert: () => triggerReaction('pervert'),
    triggerHeadPat: () => triggerReaction('headpat'),
    triggerWave: () => triggerReaction('wave'),
    triggerDance: () => triggerReaction('dance'),
    setMinimized: (min) => {
      state.minimized = !!min;
      container.classList.toggle('minimized', state.minimized);
    },
    speak: (text) => {
      showSpeechBubble(text);
      speakJarvisVoice(text);
    }
  };

  console.log('[JarvisAvatar] Full Body Avatar with Pervert & Head-Pat Reactions Mounted.');
})();
