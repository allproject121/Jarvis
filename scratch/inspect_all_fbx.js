global.self = global;
global.window = global;
const fs = require('fs');
const path = require('path');
const THREE = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three'));
const { GLTFLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/GLTFLoader.js'));
const { FBXLoader } = require(path.resolve(__dirname, '../research_repos/Anima/frontend/node_modules/three/examples/jsm/loaders/FBXLoader.js'));

const gltfLoader = new GLTFLoader();
const fbxLoader = new FBXLoader();

async function inspectAllMotions() {
  const fbxFiles = [
    'research_repos/anime-ai/humanoid-animations/Breathing Idle.fbx',
    'research_repos/anime-ai/humanoid-animations/Waving.fbx',
    'research_repos/anime-ai/humanoid-animations/Thankful.fbx',
    'research_repos/anime-ai/humanoid-animations/Clapping.fbx',
    'research_repos/anime-ai/humanoid-animations/Excited.fbx',
    'research_repos/anime-ai/humanoid-animations/Laughing.fbx',
    'research_repos/anime-ai/humanoid-animations/Thinking.fbx',
    'research_repos/anime-ai/humanoid-animations/dj/dancing/Miku.fbx',
    'research_repos/anime-ai/humanoid-animations/dj/dancing/dancing3.fbx',
    'research_repos/anime-ai/humanoid-animations/dj/interaction/Cheering.fbx',
    'research_repos/anime-ai/humanoid-animations/dj/interaction/Blowakiss.fbx'
  ];

  for (const f of fbxFiles) {
    if (!fs.existsSync(f)) continue;
    const buf = fs.readFileSync(f);
    try {
      const parsed = fbxLoader.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), '');
      const clip = parsed.animations && parsed.animations[0];
      if (clip) {
        console.log(`\nFBX: ${path.basename(f)} | Duration: ${clip.duration.toFixed(2)}s | Tracks: ${clip.tracks.length}`);
        // Check arm tracks
        const lArmTrack = clip.tracks.find(t => t.name.includes('LeftArm.rotation') || t.name.includes('mixamorigLeftArm.quaternion'));
        const lForeArmTrack = clip.tracks.find(t => t.name.includes('LeftForeArm.rotation') || t.name.includes('mixamorigLeftForeArm.quaternion'));
        console.log(`  LArm track: ${!!lArmTrack}, LForeArm track: ${!!lForeArmTrack}`);
      }
    } catch (e) {
      console.log(`Error parsing ${f}:`, e.message);
    }
  }
}

inspectAllMotions().catch(console.error);
