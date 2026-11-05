package com.jarvis.assistant.util

import android.content.Context
import com.jarvis.assistant.JarvisApp
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/**
 * Ultra-high-performance WebViewClient for Jarvis Holographic Orb & 3D Model Vault.
 * Uses AndroidX WebViewAssetLoader to serve local assets and large 3D models (.glb/.gltf/.obj/.stl)
 * over a secure https:// origin (https://appassets.androidplatform.net) with zero base64 overhead,
 * zero main-thread JS chunk loops, and full CORS / WebGL texture support.
 */
class JarvisWebViewClient(
    private val context: Context,
    private val onPageLoaded: ((view: WebView?, url: String?) -> Unit)? = null
) : WebViewClient() {

    companion object {
        private const val TAG = "JarvisWebViewClient"
        const val ASSET_DOMAIN = "appassets.androidplatform.net"
        const val ORB_URL = "https://$ASSET_DOMAIN/assets/orb.html"
        const val MORA_URL = "https://$ASSET_DOMAIN/assets/mora_character.html"
    }

    private val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .setDomain(ASSET_DOMAIN)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/vault/", WebViewAssetLoader.InternalStoragePathHandler(context, ModelVaultManager.getVaultDir(context)))
        .build()

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url ?: return null
        val path = url.path ?: ""

        // Handle CORS Preflight OPTIONS
        if (request.method.equals("OPTIONS", ignoreCase = true)) {
            val preflightHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                "Access-Control-Allow-Headers" to "*",
                "Access-Control-Max-Age" to "86400"
            )
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                200,
                "OK",
                preflightHeaders,
                ByteArrayInputStream(ByteArray(0))
            )
        }

        // 1. Intercept Retargeted Humanoid Animations Libraries (.glb)
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

        if (path.contains("hinata_animations.glb")) {
            try {
                val assetStream = context.assets.open("models/hinata_animations.glb")
                val headers = mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Methods" to "GET, OPTIONS",
                    "Access-Control-Allow-Headers" to "*",
                    "Cache-Control" to "public, max-age=86400",
                    "Content-Type" to "model/gltf-binary"
                )
                Log.i(TAG, "Streaming hinata_animations.glb from assets")
                return WebResourceResponse(
                    "model/gltf-binary",
                    null,
                    200,
                    "OK",
                    headers,
                    assetStream
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error streaming hinata_animations.glb", e)
            }
        }

        if (path.endsWith("animations.glb") && !path.contains("mora") && !path.contains("hinata")) {
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

        // 2. Intercept Motions Library (.glb)
        if (path.contains("motions/")) {
            try {
                val motionName = path.substringAfterLast("motions/").substringBefore("?")
                val assetStream = context.assets.open("motions/$motionName")
                val headers = mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Methods" to "GET, OPTIONS",
                    "Access-Control-Allow-Headers" to "*",
                    "Cache-Control" to "public, max-age=86400",
                    "Content-Type" to "model/gltf-binary"
                )
                Log.i(TAG, "Streaming motion from assets: motions/$motionName")
                return WebResourceResponse(
                    "model/gltf-binary",
                    null,
                    200,
                    "OK",
                    headers,
                    assetStream
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error streaming motion $path", e)
            }
        }

        // 2. Intercept Active 3D Model Streaming (Zero-copy native file stream)
        if (!path.contains("mora.glb") && !path.contains("mora_animations") && (path.contains("active_3d_model") || path.endsWith("active_model.glb") || (path.endsWith(".glb") && !path.contains("animations")) || path.endsWith(".gltf"))) {
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
                    Log.i(TAG, "Streaming active 3D model (${modelFile.length()} bytes): ${modelFile.name}")
                    return WebResourceResponse(
                        mimeType,
                        null,
                        200,
                        "OK",
                        headers,
                        FileInputStream(modelFile)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error streaming 3D model file: ${modelFile.absolutePath}", e)
                }
            } else {
                val activeItem = ModelVaultManager.getActiveVaultItem(context)
                val assetPath = when (activeItem.id) {
                    ModelVaultManager.BUILTIN_MORA.id -> "models/mora.glb"
                    ModelVaultManager.BUILTIN_LIVE_COMPANION.id -> "models/jett.glb"
                    else -> "models/hinata.glb"
                }
                try {
                    val assetStream = context.assets.open(assetPath)
                    val headers = mapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Access-Control-Allow-Methods" to "GET, OPTIONS",
                        "Access-Control-Allow-Headers" to "*",
                        "Cache-Control" to "no-cache, no-store, must-revalidate",
                        "Content-Type" to "model/gltf-binary"
                    )
                    Log.i(TAG, "Streaming built-in humanoid 3D model ($assetPath) from assets")
                    return WebResourceResponse(
                        "model/gltf-binary",
                        null,
                        200,
                        "OK",
                        headers,
                        assetStream
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "No model in assets ($assetPath): ${e.message}")
                }
            }
        }

        // 2. Intercept Custom Photo Texture
        if (path.contains("custom_photo")) {
            val photoFile = CustomAvatarHelper.getAvatarFile(context)
            if (photoFile.exists() && photoFile.length() > 0) {
                try {
                    val mimeType = if (photoFile.name.endsWith(".png", true)) "image/png" else "image/jpeg"
                    val headers = mapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Cache-Control" to "no-cache",
                        "Content-Type" to mimeType
                    )
                    return WebResourceResponse(
                        mimeType,
                        null,
                        200,
                        "OK",
                        headers,
                        FileInputStream(photoFile)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error streaming custom photo", e)
                }
            }
        }

        // 3. Fallback to WebViewAssetLoader for assets and vault internal files
        val assetResponse = assetLoader.shouldInterceptRequest(url)
        if (assetResponse != null) {
            return assetResponse
        }

        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageLoaded?.invoke(view, url)
    }

    override fun onReceivedError(
        view: WebView?,
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        super.onReceivedError(view, errorCode, description, failingUrl)
        Log.w(TAG, "WebView error ($errorCode: $description) on $failingUrl")
    }
}
