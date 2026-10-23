const fs = require('fs');

// 1. Update ModelVaultManager.kt
{
  const filePath = 'app/src/main/java/com/jarvis/assistant/util/ModelVaultManager.kt';
  let content = fs.readFileSync(filePath, 'utf8');
  const target = `    fun getActiveModelFile(context: Context): File? {
        val active = getActiveVaultItem(context)
        if (!active.isBuiltin && active.fileName.isNotBlank()) {
            val file = File(getVaultDir(context), active.fileName)
            if (file.exists() && file.length() > 0) return file
        }
        // Fallback: any custom model file in vault
        val allItems = getVaultItems(context)
        for (item in allItems.filter { !it.isBuiltin }.reversed()) {
            val file = File(getVaultDir(context), item.fileName)
            if (file.exists() && file.length() > 0) return file
        }
        // Fallback: legacy custom_3d_model files
        val formats = listOf("glb", "gltf", "obj", "stl")
        for (ext in formats) {
            val f = File(context.filesDir, "custom_3d_model.$ext")
            if (f.exists() && f.length() > 0) return f
        }
        return null
    }`;

  const replacement = `    fun getActiveModelFile(context: Context): File? {
        val active = getActiveVaultItem(context)
        if (active.isBuiltin) {
            // Built-in models (e.g. Jett humanoid avatar) stream directly from assets
            return null
        }
        if (!active.isBuiltin && active.fileName.isNotBlank()) {
            val file = File(getVaultDir(context), active.fileName)
            if (file.exists() && file.length() > 0) return file
        }
        // Fallback: any custom model file in vault only for custom_3d mode
        if (JarvisApp.instance.preferences.orbEntityType == "custom_3d") {
            val allItems = getVaultItems(context)
            for (item in allItems.filter { !it.isBuiltin }.reversed()) {
                val file = File(getVaultDir(context), item.fileName)
                if (file.exists() && file.length() > 0) return file
            }
            // Fallback: legacy custom_3d_model files
            val formats = listOf("glb", "gltf", "obj", "stl")
            for (ext in formats) {
                val f = File(context.filesDir, "custom_3d_model.$ext")
                if (f.exists() && file.length() > 0) return f
            }
        }
        return null
    }`;

  if (!content.includes(target)) {
    console.error('Target not found in ModelVaultManager.kt');
    process.exit(1);
  }
  content = content.replace(target, replacement);
  fs.writeFileSync(filePath, content, 'utf8');
  console.log('Successfully updated ModelVaultManager.kt');
}

// 2. Update Jarvis3DModelBridge.kt
{
  const filePath = 'app/src/main/java/com/jarvis/assistant/util/Jarvis3DModelBridge.kt';
  let content = fs.readFileSync(filePath, 'utf8');
  const target = `    @JavascriptInterface
    fun getModelFileSize(): Long {
        return getResolvedModelFile()?.length() ?: 57862972L
    }`;
  const replacement = `    @JavascriptInterface
    fun getModelFileSize(): Long {
        return getResolvedModelFile()?.length() ?: try {
            context.assets.openFd("models/jett.glb").use { it.length }
        } catch (_: Exception) {
            2991116L
        }
    }`;
  if (!content.includes(target)) {
    console.error('Target not found in Jarvis3DModelBridge.kt');
    process.exit(1);
  }
  content = content.replace(target, replacement);
  fs.writeFileSync(filePath, content, 'utf8');
  console.log('Successfully updated Jarvis3DModelBridge.kt');
}

// 3. Update JarvisWebViewClient.kt
{
  const filePath = 'app/src/main/java/com/jarvis/assistant/util/JarvisWebViewClient.kt';
  let content = fs.readFileSync(filePath, 'utf8');
  const target = `        // 1. Intercept Active 3D Model Streaming (Zero-copy native file stream)
        if (path.contains("active_3d_model") || path.endsWith("active_model.glb") || path.endsWith(".glb") || path.endsWith(".gltf")) {
            val modelFile = ModelVaultManager.getActiveModelFile(context)
                ?: CustomAvatarHelper.get3DModelFile(context)

            if (modelFile != null && modelFile.exists() && modelFile.length() > 0) {
                try {
                    val ext = modelFile.extension.lowercase()
                    val mimeType = when (ext) {
                        "glb" -> "model/gltf-binary"
                        "gltf" -> "model/gltf+json"
                        "obj" -> "text/plain"
                        "stl" -> "model/stl"
                        else -> "application/octet-stream"
                    }
                    val headers = mapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Access-Control-Allow-Methods" to "GET, OPTIONS",
                        "Access-Control-Allow-Headers" to "*",
                        "Cache-Control" to "no-cache, no-store, must-revalidate",
                        "Content-Type" to mimeType
                    )
                    Log.i(TAG, "Streaming active 3D model (\${modelFile.length()} bytes): \${modelFile.name}")
                    return WebResourceResponse(
                        mimeType,
                        null,
                        200,
                        "OK",
                        headers,
                        FileInputStream(modelFile)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error streaming 3D model file: \${modelFile.absolutePath}", e)
                }
            } else {
                // Fallback to built-in Jett model from assets
                try {
                    val assetStream = context.assets.open("models/jett.glb")
                    val headers = mapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Access-Control-Allow-Methods" to "GET, OPTIONS",
                        "Access-Control-Allow-Headers" to "*",
                        "Cache-Control" to "no-cache, no-store, must-revalidate",
                        "Content-Type" to "model/gltf-binary"
                    )
                    Log.i(TAG, "Streaming fallback built-in Jett 3D model from assets")
                    return WebResourceResponse(
                        "model/gltf-binary",
                        null,
                        200,
                        "OK",
                        headers,
                        assetStream
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "No fallback model in assets: \${e.message}")
                }
            }
        }`;

  const replacement = `        // 1. Intercept Humanoid Animations Library (.glb)
        if (path.contains("animations.glb")) {
            try {
                val assetStream = context.assets.open("models/animations.glb")
                val headers = mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Methods" to "GET, OPTIONS",
                    "Access-Control-Allow-Headers" to "*",
                    "Cache-Control" to "public, max-age=86400",
                    "Content-Type" to "model/gltf-binary"
                )
                Log.i(TAG, "Streaming animations.glb from assets")
                return WebResourceResponse(
                    "model/gltf-binary",
                    null,
                    200,
                    "OK",
                    headers,
                    assetStream
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error streaming animations.glb", e)
            }
        }

        // 2. Intercept Active 3D Model Streaming (Zero-copy native file stream)
        if (path.contains("active_3d_model") || path.endsWith("active_model.glb") || (path.endsWith(".glb") && !path.contains("animations")) || path.endsWith(".gltf")) {
            val modelFile = ModelVaultManager.getActiveModelFile(context)
                ?: if (JarvisApp.instance.preferences.orbEntityType == "custom_3d") CustomAvatarHelper.get3DModelFile(context) else null

            if (modelFile != null && modelFile.exists() && modelFile.length() > 0) {
                try {
                    val ext = modelFile.extension.lowercase()
                    val mimeType = when (ext) {
                        "glb" -> "model/gltf-binary"
                        "gltf" -> "model/gltf+json"
                        "obj" -> "text/plain"
                        "stl" -> "model/stl"
                        else -> "application/octet-stream"
                    }
                    val headers = mapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Access-Control-Allow-Methods" to "GET, OPTIONS",
                        "Access-Control-Allow-Headers" to "*",
                        "Cache-Control" to "no-cache, no-store, must-revalidate",
                        "Content-Type" to mimeType
                    )
                    Log.i(TAG, "Streaming active 3D model (\${modelFile.length()} bytes): \${modelFile.name}")
                    return WebResourceResponse(
                        mimeType,
                        null,
                        200,
                        "OK",
                        headers,
                        FileInputStream(modelFile)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error streaming 3D model file: \${modelFile.absolutePath}", e)
                }
            } else {
                // Fallback to built-in Jett humanoid model from assets
                try {
                    val assetStream = context.assets.open("models/jett.glb")
                    val headers = mapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Access-Control-Allow-Methods" to "GET, OPTIONS",
                        "Access-Control-Allow-Headers" to "*",
                        "Cache-Control" to "no-cache, no-store, must-revalidate",
                        "Content-Type" to "model/gltf-binary"
                    )
                    Log.i(TAG, "Streaming fallback built-in Jett humanoid 3D model from assets")
                    return WebResourceResponse(
                        "model/gltf-binary",
                        null,
                        200,
                        "OK",
                        headers,
                        assetStream
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "No fallback model in assets: \${e.message}")
                }
            }
        }`;

  if (!content.includes(target)) {
    console.error('Target not found in JarvisWebViewClient.kt');
    process.exit(1);
  }
  content = content.replace(target, replacement);
  fs.writeFileSync(filePath, content, 'utf8');
  console.log('Successfully updated JarvisWebViewClient.kt');
}
