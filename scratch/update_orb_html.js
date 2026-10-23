const fs = require('fs');

const orbPath = 'app/src/main/assets/orb.html';
let html = fs.readFileSync(orbPath, 'utf8').replace(/\r\n/g, '\n');

// 1. Update UniversalHumanoidEngine to support AnimationMixer clip indexing, smooth cross-fading, additive gaze, and rich facial lip-sync
const oldEngineRegex = /class UniversalHumanoidEngine \{[\s\S]*?setupKinematicDeformer\(\) \{/;

const newEngineStart = `class UniversalHumanoidEngine {
  constructor(rootObject, animations) {
    this.root = rootObject;
    this.animations = animations || [];
    this.bones = {};
    this.restRotations = {};
    this.restPositions = {};
    this.morphMeshes = [];
    this.mixer = null;
    this.actions = {};
    this.activeAction = null;
    this.activeActionName = "idle";
    this.isRigged = false;
    this.hasMorphTargets = false;
    this.hasClips = false;
    this.mappedBonesList = [];
    this.diagnostics = {};
    this.curGazeYaw = 0;
    this.curGazePitch = 0;
    this.curGazeRoll = 0;

    this.init();
    if (this.animations && this.animations.length > 0) {
      this.setAnimations(this.animations);
    }
  }

  init() {
    const BONE_PATTERNS = {
      hips: /(hips|pelvis|bip\\d*_pelvis|root_bone|mixamorig:?hips)/i,
      spine: /(spine$|spine1|lower_spine|mixamorig:?spine$)/i,
      chest: /(chest|upperchest|spine2|mixamorig:?spine1|mixamorig:?spine2)/i,
      neck: /(neck|mixamorig:?neck)/i,
      head: /(head|mixamorig:?head)/i,
      leftShoulder: /(leftshoulder|shoulder_l|l_shoulder|mixamorig:?leftshoulder)/i,
      leftUpperArm: /(leftarm|leftupperarm|arm_l|upperarm_l|l_arm|mixamorig:?leftarm)/i,
      leftForearm: /(leftforearm|forearm_l|lowerarm_l|l_forearm|mixamorig:?leftforearm)/i,
      leftHand: /(lefthand|hand_l|l_hand|mixamorig:?lefthand)/i,
      rightShoulder: /(rightshoulder|shoulder_r|r_shoulder|mixamorig:?rightshoulder)/i,
      rightUpperArm: /(rightarm|rightupperarm|arm_r|upperarm_r|r_arm|mixamorig:?rightarm)/i,
      rightForearm: /(rightforearm|forearm_r|lowerarm_r|r_forearm|mixamorig:?rightforearm)/i,
      rightHand: /(righthand|hand_r|r_hand|mixamorig:?righthand)/i,
      leftThigh: /(leftupleg|leftthigh|thigh_l|upperleg_l|l_leg|mixamorig:?leftupleg)/i,
      leftCalf: /(leftleg|leftcalf|calf_l|lowerleg_l|l_calf|leftshin|mixamorig:?leftleg)/i,
      leftFoot: /(leftfoot|foot_l|l_foot|mixamorig:?leftfoot)/i,
      rightThigh: /(rightupleg|rightthigh|thigh_r|upperleg_r|r_leg|mixamorig:?rightupleg)/i,
      rightCalf: /(rightleg|rightcalf|calf_r|lowerleg_r|r_calf|rightshin|mixamorig:?rightleg)/i,
      rightFoot: /(rightfoot|foot_r|r_foot|mixamorig:?rightfoot)/i
    };

    let totalMeshes = 0;
    const self = this;

    this.root.traverse(function(child) {
      if (child.isMesh) {
        totalMeshes++;
        if (child.morphTargetDictionary && Object.keys(child.morphTargetDictionary).length > 0) {
          self.morphMeshes.push(child);
          self.hasMorphTargets = true;
        }
      }

      const name = child.name || "";
      for (const [key, regex] of Object.entries(BONE_PATTERNS)) {
        if (!self.bones[key] && regex.test(name)) {
          self.bones[key] = child;
          self.restRotations[key] = child.rotation.clone();
          self.restPositions[key] = child.position.clone();
          self.mappedBonesList.push(key);
          break;
        }
      }
    });

    if (this.mappedBonesList.length >= 3 || this.bones.head || this.bones.leftUpperArm || this.bones.hips) {
      this.isRigged = true;
    } else {
      this.setupKinematicDeformer();
    }

    this.updateDiagnostics(totalMeshes);
  }

  setAnimations(animations) {
    if (!animations || animations.length === 0) return;
    this.animations = animations;
    if (!this.mixer) {
      this.mixer = new THREE.AnimationMixer(this.root);
    }
    this.actions = {};
    for (let i = 0; i < animations.length; i++) {
      const clip = animations[i];
      const rawName = (clip.name || "").toLowerCase();
      try {
        const action = this.mixer.clipAction(clip);
        action.setLoop(THREE.LoopRepeat);
        this.actions[rawName] = action;
        if (rawName.includes("idle")) this.actions["idle"] = action;
        if (rawName.includes("talking_0") || rawName === "talking0") this.actions["talking_0"] = action;
        if (rawName.includes("talking_1") || rawName === "talking1") this.actions["talking_1"] = action;
        if (rawName.includes("talking_2") || rawName === "talking2") this.actions["talking_2"] = action;
        if (rawName.includes("talking") && !this.actions["talking_0"]) this.actions["talking_0"] = action;
        if (rawName.includes("laugh")) this.actions["laughing"] = action;
        if (rawName.includes("angry")) this.actions["angry"] = action;
        if (rawName.includes("cry")) this.actions["crying"] = action;
        if (rawName.includes("rumba") || rawName.includes("dance")) this.actions["rumba"] = action;
        if (rawName.includes("terrif") || rawName.includes("fear")) this.actions["terrified"] = action;
      } catch (err) {
        console.warn("Error creating clip action for", clip.name, err);
      }
    }
    this.hasClips = Object.keys(this.actions).length > 0;
    this.fadeToAction("idle", 0.1);
    this.updateDiagnostics();
  }

  fadeToAction(name, duration) {
    duration = (duration !== undefined && duration !== null) ? duration : 0.35;
    if (!this.mixer) return;
    const targetAction = this.actions[name] || this.actions["idle"];
    if (!targetAction) return;

    if (this.activeAction === targetAction) {
      if (!targetAction.isRunning()) targetAction.play();
      return;
    }

    const prevAction = this.activeAction;
    this.activeAction = targetAction;
    this.activeActionName = name;

    targetAction.reset();
    targetAction.setEffectiveTimeScale(1);
    targetAction.setEffectiveWeight(1);
    targetAction.clampWhenFinished = false;

    if (prevAction && prevAction !== targetAction) {
      prevAction.crossFadeTo(targetAction, duration, true);
    }
    targetAction.play();
  }

  updateDiagnostics(meshCount) {
    this.diagnostics = {
      isRigged: this.isRigged,
      rigType: this.isRigged ? (this.mappedBonesList.length >= 10 ? "Full Humanoid Armature" : "Partial Humanoid Rig") : "GPU Kinematic Humanoid (T-Pose Lowered & Animated)",
      meshCount: meshCount !== undefined ? meshCount : (this.diagnostics.meshCount || 0),
      boneCount: this.mappedBonesList.length,
      mappedBones: this.mappedBonesList,
      hasAnimations: this.hasClips || (this.animations && this.animations.length > 0),
      animationClipsCount: this.animations ? this.animations.length : 0,
      activeClip: this.activeActionName,
      hasMorphTargets: this.hasMorphTargets,
      statusMessage: this.isRigged
        ? "Rigged Humanoid Armature Active (" + this.mappedBonesList.length + " joints mapped). Skeletal animations (" + (this.animations ? this.animations.length : 0) + " clips) & ARKit facial visemes active."
        : "Kinematic Humanoid Engine active. Fallback vertex deformation active."
    };

    console.log("UniversalHumanoidEngine diagnostics:", this.diagnostics);

    if (window.Jarvis3DModelBridge && typeof window.Jarvis3DModelBridge.reportModelDiagnostics === "function") {
      try {
        window.Jarvis3DModelBridge.reportModelDiagnostics(JSON.stringify(this.diagnostics));
      } catch (err) {
        console.warn("Could not report diagnostics to bridge:", err);
      }
    }
  }

  setupKinematicDeformer() {`;

if (!oldEngineRegex.test(html)) {
  console.error("Could not find old UniversalHumanoidEngine start");
  process.exit(1);
}
html = html.replace(oldEngineRegex, newEngineStart);
console.log("Updated UniversalHumanoidEngine class header & methods");

// 2. Update UniversalHumanoidEngine.update(...) method
const oldUpdateRegex = /update\(dt, t, state, audio, gazeX, gazeY, activeEmote, emoteTimer\) \{[\s\S]*?\/\/ GPU Kinematic Humanoid Deformation for Unrigged Models/;

const newUpdateCode = `update(dt, t, state, audio, gazeX, gazeY, activeEmote, emoteTimer) {
    // 1. Determine & cross-fade active animation clip
    if (this.hasClips && this.mixer) {
      let desiredClip = "idle";
      const isSpeaking = (state === "speaking") || (audio.level > 0.04);
      const isListening = (state === "listening");
      const isThinking = (state === "thinking");

      if (activeEmote === "rumba" || activeEmote === "dance") {
        desiredClip = "rumba";
      } else if (activeEmote === "laugh" || activeEmote === "happy") {
        desiredClip = "laughing";
      } else if (activeEmote === "angry") {
        desiredClip = "angry";
      } else if (activeEmote === "crying" || activeEmote === "sad") {
        desiredClip = "crying";
      } else if (activeEmote === "terrified" || activeEmote === "fear") {
        desiredClip = "terrified";
      } else if (isSpeaking) {
        // Cycle natural conversational talking gestures
        const talkCycle = Math.floor((t / 2.6) % 3);
        desiredClip = talkCycle === 1 ? "talking_1" : (talkCycle === 2 ? "talking_2" : "talking_0");
      } else {
        desiredClip = "idle";
      }

      this.fadeToAction(desiredClip, 0.45);
      this.mixer.update(dt);
    }

    // 2. Facial Morph Targets: Real-time Audio Lip Sync & Natural Blinking
    if (this.hasMorphTargets) {
      const isTalking = (state === "speaking") || (audio.level > 0.04);
      const mouthOpen = isTalking ? Math.min(1.0, audio.level * 3.4 + Math.abs(Math.sin(t * 11)) * 0.35) : 0;
      const visemeO = isTalking ? Math.min(0.85, Math.abs(Math.cos(t * 7.5)) * audio.level * 2.8) : 0;
      const visemeE = isTalking ? Math.min(0.75, Math.abs(Math.sin(t * 9.2)) * audio.level * 2.2) : 0;

      // Realistic human eyelid blinking: ~130ms blink duration every 3.8s
      const blinkCycle = t % 3.8;
      const isBlinking = blinkCycle < 0.14 ? 1.0 : 0.0;
      const gentleSmile = 0.15; // Natural resting friendly expression

      for (const mesh of this.morphMeshes) {
        const dict = mesh.morphTargetDictionary;
        const infl = mesh.morphTargetInfluences;
        if (!dict || !infl) continue;

        // Mouth & Jaw lip-sync opening
        for (const mName of ["mouthOpen", "jawOpen", "viseme_aa", "mouth_open", "MouthOpen"]) {
          if (dict[mName] !== undefined) {
            infl[dict[mName]] = THREE.MathUtils.lerp(infl[dict[mName]], mouthOpen, dt * 20);
          }
        }
        for (const mName of ["viseme_O", "viseme_U"]) {
          if (dict[mName] !== undefined) {
            infl[dict[mName]] = THREE.MathUtils.lerp(infl[dict[mName]], visemeO, dt * 16);
          }
        }
        for (const mName of ["viseme_E", "viseme_I"]) {
          if (dict[mName] !== undefined) {
            infl[dict[mName]] = THREE.MathUtils.lerp(infl[dict[mName]], visemeE, dt * 16);
          }
        }

        // Bilateral eyelid blinking
        for (const bName of ["blink", "blink_left", "blink_right", "eyeBlinkLeft", "eyeBlinkRight", "Blink"]) {
          if (dict[bName] !== undefined) {
            infl[dict[bName]] = THREE.MathUtils.lerp(infl[dict[bName]], isBlinking, dt * 30);
          }
        }

        // Gentle smiling facial morphs
        for (const sName of ["mouthSmileLeft", "mouthSmileRight", "mouth_smile_left", "mouth_smile_right", "Smile"]) {
          if (dict[sName] !== undefined) {
            infl[dict[sName]] = THREE.MathUtils.lerp(infl[dict[sName]], gentleSmile, dt * 10);
          }
        }
      }
    }

    // 3. Additive Head Gaze Tracking & Tilts
    if (this.isRigged && this.bones.head) {
      const gazeYaw = (typeof gazeX !== "undefined" ? gazeX : 0) * 0.38;
      const gazePitch = -(typeof gazeY !== "undefined" ? gazeY : 0) * 0.26;
      let emoteHeadTiltZ = 0, emoteHeadPitchX = 0;

      if (activeEmote === "curious" || state === "listening") {
        emoteHeadTiltZ = 0.12;
        emoteHeadPitchX = -0.06;
      } else if (activeEmote === "wave") {
        emoteHeadTiltZ = 0.08;
      } else if (state === "thinking") {
        emoteHeadTiltZ = -0.10;
        emoteHeadPitchX = -0.12;
      }

      this.curGazeYaw = THREE.MathUtils.lerp(this.curGazeYaw, gazeYaw, dt * 5.0);
      this.curGazePitch = THREE.MathUtils.lerp(this.curGazePitch, gazePitch + emoteHeadPitchX, dt * 5.0);
      this.curGazeRoll = THREE.MathUtils.lerp(this.curGazeRoll, emoteHeadTiltZ, dt * 5.0);

      if (this.hasClips) {
        // Additive gaze rotation on top of animated skeletal head motion
        this.bones.head.rotation.y += this.curGazeYaw;
        this.bones.head.rotation.x += this.curGazePitch;
        this.bones.head.rotation.z += this.curGazeRoll;
      } else if (this.restRotations.head) {
        // Fallback procedural head positioning
        const rest = this.restRotations.head;
        const nod = (state === "speaking") ? Math.sin(t * 6.5) * (0.04 + audio.level * 0.08) : 0;
        this.bones.head.rotation.y = THREE.MathUtils.lerp(this.bones.head.rotation.y, rest.y + this.curGazeYaw, dt * 6.0);
        this.bones.head.rotation.x = THREE.MathUtils.lerp(this.bones.head.rotation.x, rest.x + this.curGazePitch + nod, dt * 6.0);
        this.bones.head.rotation.z = THREE.MathUtils.lerp(this.bones.head.rotation.z, rest.z + this.curGazeRoll, dt * 6.0);
      }
    }

    // 4. Procedural Arm/Spine Fallback ONLY if skeletal animation clips are absent
    if (this.isRigged && !this.hasClips) {
      const isSpeaking = (state === "speaking") || (audio.level > 0.04);
      const isListening = (state === "listening");
      const isThinking = (state === "thinking");
      const breath = Math.sin(t * 2.2);

      if (this.bones.chest && this.restRotations.chest) {
        this.bones.chest.rotation.x = this.restRotations.chest.x + breath * 0.025;
      }
      if (this.bones.spine && this.restRotations.spine) {
        this.bones.spine.rotation.z = this.restRotations.spine.z + Math.sin(t * 0.8) * 0.015;
      }
      if (this.bones.rightUpperArm && this.bones.leftUpperArm && this.restRotations.rightUpperArm && this.restRotations.leftUpperArm) {
        const restRA = this.restRotations.rightUpperArm;
        const restLA = this.restRotations.leftUpperArm;
        const armSway = Math.sin(t * 1.5) * 0.03;
        this.bones.rightUpperArm.rotation.x = THREE.MathUtils.lerp(this.bones.rightUpperArm.rotation.x, restRA.x + 0.10 + armSway, dt * 4);
        this.bones.leftUpperArm.rotation.x = THREE.MathUtils.lerp(this.bones.leftUpperArm.rotation.x, restLA.x + 0.10 - armSway, dt * 4);
        if (this.bones.rightForearm && this.restRotations.rightForearm) this.bones.rightForearm.rotation.x = THREE.MathUtils.lerp(this.bones.rightForearm.rotation.x, -0.22, dt * 4);
        if (this.bones.leftForearm && this.restRotations.leftForearm) this.bones.leftForearm.rotation.x = THREE.MathUtils.lerp(this.bones.leftForearm.rotation.x, -0.22, dt * 4);
      }
    }

    // GPU Kinematic Humanoid Deformation for Unrigged Models`;

if (!oldUpdateRegex.test(html)) {
  console.error("Could not find old UniversalHumanoidEngine.update method");
  process.exit(1);
}
html = html.replace(oldUpdateRegex, newUpdateCode);
console.log("Updated UniversalHumanoidEngine.update method");

// 3. Update onModelReady orientation (faces user directly +Z) & auto-load animation library if missing
const oldOnModelReadyOrientation = `  // Face camera directly (+Z) - Jett's front is +X, so rotation.y = -Math.PI / 2 aligns her face forward to user
  object.rotation.y = -Math.PI / 2;`;

const newOnModelReadyOrientation = `  // Face camera directly (+Z) - standard Ready Player Me / Mixamo humanoid faces +Z directly
  object.rotation.y = 0;`;

if (!html.includes(oldOnModelReadyOrientation)) {
  console.error("Could not find oldOnModelReadyOrientation");
  process.exit(1);
}
html = html.replace(oldOnModelReadyOrientation, newOnModelReadyOrientation);
console.log("Updated model orientation to face user directly (rotation.y = 0)");

// 4. Update onModelReady animation attachment: if no animations, load animations.glb
const oldModelReadyEngineInit = `  // Initialize Universal Humanoid Engine
  try {
    customModelEngine = new UniversalHumanoidEngine(object, animations);
    if (customModelEngine.mixer) {
      customModelMixer = customModelEngine.mixer;
    }
  } catch (err) {
    console.warn("UniversalHumanoidEngine init error:", err);
  }`;

const newModelReadyEngineInit = `  // Initialize Universal Humanoid Engine
  try {
    customModelEngine = new UniversalHumanoidEngine(object, animations);
    if (customModelEngine.mixer) {
      customModelMixer = customModelEngine.mixer;
    }
    // If animations are not embedded in the model, fetch retargeted animations library
    if (!animations || animations.length === 0) {
      const animUrl = "https://appassets.androidplatform.net/assets/models/animations.glb";
      if (typeof THREE.GLTFLoader !== "undefined") {
        console.log("Loading retargeted humanoid animations library from:", animUrl);
        new THREE.GLTFLoader().load(
          animUrl,
          function(animGltf) {
            console.log("Loaded humanoid animation library:", animGltf.animations.length, "clips");
            if (customModelEngine && animGltf.animations && animGltf.animations.length > 0) {
              customModelEngine.setAnimations(animGltf.animations);
              customModelMixer = customModelEngine.mixer;
            }
          },
          undefined,
          function(err) {
            console.warn("Could not load animations.glb:", err);
          }
        );
      }
    }
  } catch (err) {
    console.warn("UniversalHumanoidEngine init error:", err);
  }`;

if (!html.includes(oldModelReadyEngineInit)) {
  console.error("Could not find oldModelReadyEngineInit");
  process.exit(1);
}
html = html.replace(oldModelReadyEngineInit, newModelReadyEngineInit);
console.log("Updated onModelReady to auto-load humanoid animations library");

// 5. Expose triggerReaction & setAnimState on window.JarvisOrb
const oldOrbExports = `  triggerReaction: triggerReaction,
  triggerTouchReaction: triggerTouchReaction,
  triggerWave: () => triggerReaction("wave"),`;

const newOrbExports = `  triggerReaction: triggerReaction,
  triggerTouchReaction: triggerTouchReaction,
  triggerWave: () => triggerReaction("wave"),
  setEmote: (emote) => triggerReaction(emote),
  setAnimState: (s) => {
    setOrbState(s);
    if (customModelEngine) customModelEngine.fadeToAction(s === "speaking" ? "talking_0" : s, 0.35);
  },`;

if (!html.includes(oldOrbExports)) {
  console.error("Could not find oldOrbExports");
  process.exit(1);
}
html = html.replace(oldOrbExports, newOrbExports);
console.log("Updated window.JarvisOrb exports");

fs.writeFileSync(orbPath, html, 'utf8');
console.log("Successfully updated app/src/main/assets/orb.html");
