const fs = require('fs');
const filePath = 'app/src/main/java/com/jarvis/assistant/util/JarvisWebViewClient.kt';
let content = fs.readFileSync(filePath, 'utf8');

const isCRLF = content.includes('\r\n');
let normalized = content.replace(/\r\n/g, '\n');

const target1 = `        // 1. Intercept Retargeted Humanoid Animations Libraries (.glb)
        if (path.contains("hinata_animations.glb")) {`;

const replacement1 = `        // 1. Intercept Retargeted Humanoid Animations Libraries (.glb)
        if (path.contains("mora_animations.glb")) {
            try {
                val assetStream = context.assets.open("models/mora_animations.glb")
                val headers = mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Methods" to "GET, OPTIONS",
                    "Access-Control-Allow-Headers" to "*",
                    "Cache-Control" to "no-cache, no-store, must-revalidate",
                    "Content-Type" to "model/gltf-binary"
                )
                Log.i(TAG, "Streaming mora_animations.glb from assets")
                return WebResourceResponse(
                    "model/gltf-binary",
                    null,
                    200,
                    "OK",
                    headers,
                    assetStream
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error streaming mora_animations.glb", e)
            }
        }

        if (path.endsWith("mora.glb")) {
            try {
                val assetStream = context.assets.open("models/mora.glb")
                val headers = mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Methods" to "GET, OPTIONS",
                    "Access-Control-Allow-Headers" to "*",
                    "Cache-Control" to "no-cache, no-store, must-revalidate",
                    "Content-Type" to "model/gltf-binary"
                )
                Log.i(TAG, "Streaming exact built-in models/mora.glb from assets")
                return WebResourceResponse(
                    "model/gltf-binary",
                    null,
                    200,
                    "OK",
                    headers,
                    assetStream
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error streaming exact models/mora.glb", e)
            }
        }

        if (path.contains("hinata_animations.glb")) {`;

if (!normalized.includes(target1)) {
  console.error('Target1 not found!');
  process.exit(1);
}

let updated = normalized.replace(target1, replacement1);

// Also fix: if (path.contains("animations.glb"))
updated = updated.replace(
  'if (path.contains("animations.glb"))',
  'if (path.endsWith("animations.glb") && !path.contains("mora") && !path.contains("hinata"))'
);

// Also fix: Active 3D model streaming condition so it doesn't hijack mora.glb
updated = updated.replace(
  'if (path.contains("active_3d_model") || path.endsWith("active_model.glb") || (path.endsWith(".glb") && !path.contains("animations")) || path.endsWith(".gltf"))',
  'if (!path.contains("mora.glb") && !path.contains("mora_animations") && (path.contains("active_3d_model") || path.endsWith("active_model.glb") || (path.endsWith(".glb") && !path.contains("animations")) || path.endsWith(".gltf")))'
);

if (isCRLF) updated = updated.replace(/\n/g, '\r\n');
fs.writeFileSync(filePath, updated, 'utf8');
console.log('Successfully updated JarvisWebViewClient.kt');
