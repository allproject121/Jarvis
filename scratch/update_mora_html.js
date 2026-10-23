const fs = require('fs');

let html = fs.readFileSync('app/src/main/assets/mora_character.html', 'utf8');

// 1. Replace setAnimations
const oldSetAnim = /setAnimations\(clips\) \{[\s\S]*?this\.fadeToAction\("idle", 0\.1\);\s*\}/;
const newSetAnim = `setAnimations(clips) {
        if (!clips || clips.length === 0) return;
        for (let i = 0; i < clips.length; i++) {
          const clip = clips[i];
          const rawName = (clip.name || "").toLowerCase();
          try {
            const action = this.mixer.clipAction(clip);
            action.setLoop(THREE.LoopRepeat);
            this.actions[rawName] = action;
            if (rawName.includes("idle")) this.actions["idle"] = action;
            if (rawName.includes("talking") || rawName.includes("talk") || rawName.includes("speak")) {
              this.actions["talking"] = action;
              this.actions["talking_0"] = action;
              this.actions["talking_1"] = action;
            }
            if (rawName.includes("wave")) this.actions["wave"] = action;
            if (rawName.includes("joy") || rawName.includes("laugh") || rawName.includes("cheer")) this.actions["joy"] = action;
            if (rawName.includes("learn") || rawName.includes("teach") || rawName.includes("thankful")) {
              this.actions["learn"] = action;
              this.actions["teach"] = action;
            }
            if (rawName.includes("dance")) this.actions["dance"] = action;
            if (rawName.includes("thinking") || rawName.includes("think")) this.actions["thinking"] = action;
            if (rawName.includes("crying") || rawName.includes("cry")) this.actions["crying"] = action;
          } catch (e) {
            console.warn("Error creating action for clip", clip.name, e);
          }
        }
        this.hasClips = Object.keys(this.actions).length > 0;
        console.log("Mora clips registered:", Object.keys(this.actions));
        this.fadeToAction("idle", 0.1);
      }`;

if (!oldSetAnim.test(html)) throw new Error('oldSetAnim not matched');
html = html.replace(oldSetAnim, newSetAnim);

// 2. Replace action selection in update
const oldDesired = /let desired = "idle";[\s\S]*?this\.fadeToAction\(desired, 0\.35\);\s*this\.mixer\.update\(dt\);/;
const newDesired = `let desired = "idle";
          if (activeReaction === "dance" || activeReaction === "rumba") desired = "dance";
          else if (activeReaction === "joy" || activeReaction === "laugh") desired = "joy";
          else if (activeReaction === "wave") desired = "wave";
          else if (activeReaction === "teach" || activeReaction === "learn" || activeReaction === "nod") desired = "learn";
          else if (activeReaction === "curious" || activeReaction === "thinking") desired = "thinking";
          else if (activeReaction === "cry") desired = "crying";
          else if (state === "speaking") {
            desired = "talking";
          } else if (state === "thinking") {
            desired = "thinking";
          } else {
            desired = "idle";
          }
          this.fadeToAction(desired, 0.35);
          this.mixer.update(dt);`;

if (!oldDesired.test(html)) throw new Error('oldDesired not matched');
html = html.replace(oldDesired, newDesired);

// 3. Replace head kinematics
const oldHead = /\/\/ 3\. Additive Kinematics:[\s\S]*?this\.bones\.head\.quaternion\.multiply\(additiveQ\);\s*\}/;
const newHead = `// 3. Additive Kinematics: Clamped Gaze Tracking & Conversational Head Tilts
        if (this.hasClips && this.bones.head) {
          const targetGazeX = this.gazeX * 0.38;
          const targetGazeY = -this.gazeY * 0.26;
          let tiltZ = 0, pitchX = 0;

          if (state === "listening") {
            tiltZ = 0.08;
            pitchX = -0.04;
          } else if (state === "thinking") {
            tiltZ = -0.10;
            pitchX = -0.06;
          } else if (activeReaction === "wave") {
            tiltZ = 0.06;
          } else if (activeReaction === "teach" || activeReaction === "learn" || activeReaction === "nod") {
            pitchX = -Math.abs(Math.sin(time * 3.2)) * 0.08;
          } else if (activeReaction === "curious") {
            tiltZ = 0.12;
            pitchX = -0.04;
          }

          const speechNod = isSpeaking ? -Math.abs(Math.sin(time * 3.0)) * (0.02 + rawVol * 0.04) : 0;

          this.curGazeX = THREE.MathUtils.lerp(this.curGazeX, targetGazeX, dt * 5.0);
          this.curGazeY = THREE.MathUtils.lerp(this.curGazeY, targetGazeY + pitchX + speechNod, dt * 5.0);
          this.headRoll = THREE.MathUtils.lerp(this.headRoll, tiltZ, dt * 5.0);

          const clampedPitch = Math.max(-0.15, Math.min(0.08, this.curGazeY));
          const clampedYaw = Math.max(-0.25, Math.min(0.25, this.curGazeX));
          const clampedRoll = Math.max(-0.15, Math.min(0.15, this.headRoll));

          const additiveQ = new THREE.Quaternion().setFromEuler(new THREE.Euler(clampedPitch, clampedYaw, clampedRoll, 'YXZ'));
          this.bones.head.quaternion.multiply(additiveQ);
        }`;

if (!oldHead.test(html)) throw new Error('oldHead not matched');
html = html.replace(oldHead, newHead);

fs.writeFileSync('app/src/main/assets/mora_character.html', html, 'utf8');
console.log('Successfully updated mora_character.html!');
