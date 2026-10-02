package com.example.myapplication

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class JarvisScreenAnalyzer {

    data class ScreenElement(
        val text: String,
        val contentDescription: String,
        val className: String,
        val viewId: String,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isChecked: Boolean,
        val isCheckable: Boolean,
        val isFocusable: Boolean,
        val isFocused: Boolean,
        val isScrollable: Boolean,
        val isLongClickable: Boolean,
        val isPassword: Boolean,
        val isVisible: Boolean,
        val bounds: Rect,
        val centerX: Float,
        val centerY: Float,
        val depth: Int,
        val packageName: String
    )

    data class ScreenAnalysis(
        val allElements: List<ScreenElement>,
        val clickableButtons: List<ScreenElement>,
        val editableFields: List<ScreenElement>,
        val textElements: List<ScreenElement>,
        val imageElements: List<ScreenElement>,
        val checkboxes: List<ScreenElement>,
        val scrollableAreas: List<ScreenElement>,
        val searchBars: List<ScreenElement>,
        val navigationElements: List<ScreenElement>
    )

    private var lastAnalysis: ScreenAnalysis? = null

    fun analyzeScreen(rootNode: AccessibilityNodeInfo?): ScreenAnalysis {
        if (rootNode == null) {
            val empty = ScreenAnalysis(emptyList(), emptyList(), emptyList(), emptyList(),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            lastAnalysis = empty
            return empty
        }

        val allElements = mutableListOf<ScreenElement>()
        traverseNode(rootNode, allElements, 0)

        val analysis = ScreenAnalysis(
            allElements = allElements,
            clickableButtons = allElements.filter { it.isClickable && it.text.isNotBlank() },
            editableFields = allElements.filter { it.isEditable },
            textElements = allElements.filter { it.text.isNotBlank() },
            imageElements = allElements.filter { it.className.contains("Image", true) },
            checkboxes = allElements.filter { it.isCheckable },
            scrollableAreas = allElements.filter { it.isScrollable },
            searchBars = detectSearchBars(allElements),
            navigationElements = detectNavigationElements(allElements)
        )

        lastAnalysis = analysis
        return analysis
    }

    private fun detectSearchBars(elements: List<ScreenElement>): List<ScreenElement> {
        return elements.filter { el ->
            val text = el.text.lowercase()
            val desc = el.contentDescription.lowercase()
            val id = el.viewId.lowercase()
            el.isEditable &&
            (text.contains("search") || desc.contains("search") || id.contains("search") ||
             text.contains("dhoond") || desc.contains("dhoond") || text.contains("find") ||
             text.contains("type") || id.contains("input") || id.contains("text_input"))
        }
    }

    private fun detectNavigationElements(elements: List<ScreenElement>): List<ScreenElement> {
        val navKeywords = listOf("back", "home", "menu", "navigation", "nav", "drawer",
            "up", "close", "cancel", "arrow", "piche", "ghar", "wapis")
        return elements.filter { el ->
            val text = el.text.lowercase()
            val desc = el.contentDescription.lowercase()
            navKeywords.any { text.contains(it) || desc.contains(it) }
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo, list: MutableList<ScreenElement>, depth: Int) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val element = ScreenElement(
            text = node.text?.toString() ?: "",
            contentDescription = node.contentDescription?.toString() ?: "",
            className = node.className?.toString() ?: "",
            viewId = node.viewIdResourceName ?: "",
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isChecked = node.isChecked,
            isCheckable = node.isCheckable,
            isFocusable = node.isFocusable,
            isFocused = node.isFocused,
            isScrollable = node.isScrollable,
            isLongClickable = node.isLongClickable,
            isPassword = node.isPassword,
            isVisible = node.isVisibleToUser,
            bounds = bounds,
            centerX = (bounds.left + bounds.right) / 2f,
            centerY = (bounds.top + bounds.bottom) / 2f,
            depth = depth,
            packageName = node.packageName?.toString() ?: ""
        )
        list.add(element)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, list, depth + 1)
        }
    }

    fun findElementByText(text: String): ScreenElement? {
        val analysis = lastAnalysis ?: return null
        val lower = text.lowercase()
        return analysis.allElements.find {
            it.text.lowercase().contains(lower) || it.contentDescription.lowercase().contains(lower)
        }
    }

    fun findElementByViewId(viewId: String): ScreenElement? {
        val analysis = lastAnalysis ?: return null
        return analysis.allElements.find { it.viewId.contains(viewId) }
    }

    fun findClickableByText(text: String): ScreenElement? {
        val analysis = lastAnalysis ?: return null
        val lower = text.lowercase()
        return analysis.clickableButtons.find {
            it.text.lowercase().contains(lower) || it.contentDescription.lowercase().contains(lower)
        }
    }

    fun getScreenSummary(): String {
        val analysis = lastAnalysis ?: return "Screen analysis not available"
        return buildString {
            appendLine("=== SCREEN ANALYSIS ===")
            appendLine("Total elements: ${analysis.allElements.size}")
            appendLine("Buttons: ${analysis.clickableButtons.size}")
            appendLine("Input fields: ${analysis.editableFields.size}")
            appendLine("Text elements: ${analysis.textElements.size}")
            if (analysis.searchBars.isNotEmpty()) {
                appendLine("Search bars: ${analysis.searchBars.size}")
                analysis.searchBars.forEach {
                    appendLine("  - Search: ${it.text.take(30)} at (${it.centerX.toInt()},${it.centerY.toInt()})")
                }
            }
            if (analysis.navigationElements.isNotEmpty()) {
                appendLine("Navigation:")
                analysis.navigationElements.forEach {
                    appendLine("  - ${it.text.take(30)} (${it.centerX.toInt()},${it.centerY.toInt()})")
                }
            }
            if (analysis.clickableButtons.isNotEmpty()) {
                appendLine("Clickable buttons:")
                analysis.clickableButtons.take(10).forEach {
                    appendLine("  - ${it.text.take(30)} at (${it.centerX.toInt()},${it.centerY.toInt()})")
                }
            }
        }
    }

    fun getElementsAsJSON(): String {
        val analysis = lastAnalysis ?: return "[]"
        val arr = JSONArray()
        analysis.allElements.forEach { el ->
            val obj = JSONObject()
            obj.put("text", el.text)
            obj.put("contentDesc", el.contentDescription)
            obj.put("className", el.className)
            obj.put("clickable", el.isClickable)
            obj.put("editable", el.isEditable)
            obj.put("centerX", el.centerX.toInt())
            obj.put("centerY", el.centerY.toInt())
            obj.put("width", el.bounds.width())
            obj.put("height", el.bounds.height())
            arr.put(obj)
        }
        return arr.toString()
    }

    fun findElementAt(x: Float, y: Float): ScreenElement? {
        val analysis = lastAnalysis ?: return null
        return analysis.allElements.find { el ->
            x >= el.bounds.left && x <= el.bounds.right &&
            y >= el.bounds.top && y <= el.bounds.bottom
        }
    }

    fun identifyAppSpecificElements(packageName: String): Map<String, List<ScreenElement>> {
        val analysis = lastAnalysis ?: return emptyMap()
        val result = mutableMapOf<String, List<ScreenElement>>()

        when {
            packageName.contains("whatsapp") -> {
                result["search_bar"] = analysis.editableFields.filter {
                    it.viewId.contains("search") || it.bounds.top < 200
                }
                result["chat_list"] = analysis.allElements.filter {
                    it.className.contains("ListView") || it.className.contains("RecyclerView")
                }
                result["message_input"] = analysis.editableFields.filter {
                    it.viewId.contains("entry") || it.bounds.bottom > 2000
                }
                result["send_button"] = analysis.clickableButtons.filter {
                    it.viewId.contains("send") || it.text.contains("Send")
                }
                result["back_button"] = analysis.clickableButtons.filter {
                    it.contentDescription.contains("back", true) || it.viewId.contains("back", true)
                }
            }
            packageName.contains("youtube") || packageName.contains("youtube") -> {
                result["search_bar"] = analysis.editableFields.filter {
                    it.viewId.contains("search")
                }
                result["video_list"] = analysis.allElements.filter {
                    it.className.contains("RecyclerView") || it.className.contains("ListView")
                }
            }
            packageName.contains("tiktok") || packageName.contains("musically") || packageName.contains("triller") -> {
                result["like_button"] = analysis.clickableButtons.filter {
                    it.contentDescription.contains("like", true) || it.viewId.contains("like", true)
                }
                result["comment_button"] = analysis.clickableButtons.filter {
                    it.contentDescription.contains("comment", true) || it.viewId.contains("comment", true)
                }
                result["save_button"] = analysis.clickableButtons.filter {
                    it.contentDescription.contains("save", true) || it.contentDescription.contains("bookmark", true)
                }
                result["share_button"] = analysis.clickableButtons.filter {
                    it.contentDescription.contains("share", true) || it.viewId.contains("share", true)
                }
                result["comment_input"] = analysis.editableFields.filter {
                    it.viewId.contains("comment") || it.contentDescription.contains("comment", true)
                }
            }
            packageName.contains("instagram") || packageName.contains("instagram") -> {
                result["like_button"] = analysis.clickableButtons.filter {
                    it.contentDescription.contains("like", true)
                }
                result["comment_button"] = analysis.clickableButtons.filter {
                    it.contentDescription.contains("comment", true)
                }
                result["comment_input"] = analysis.editableFields.filter {
                    it.viewId.contains("comment") || it.contentDescription.contains("comment", true)
                }
            }
        }

        return result
    }

    fun getElementDescription(element: ScreenElement): String {
        return buildString {
            append(if (element.text.isNotBlank()) element.text else if (element.contentDescription.isNotBlank()) element.contentDescription else "unnamed element")
            append(" (${element.className.substringAfterLast(".")})")
            append(" at ${element.centerX.toInt()},${element.centerY.toInt()}")
            if (element.isClickable) append(" [CLICKABLE]")
            if (element.isEditable) append(" [EDITABLE]")
        }
    }

    // Extract chat/conversation names from WhatsApp
    fun extractChatNames(): List<String> {
        val analysis = lastAnalysis ?: return emptyList()
        val chatNames = mutableListOf<String>()
        
        for (el in analysis.allElements) {
            val text = el.text.trim()
            if (text.isNotEmpty() && text.length in 2..50) {
                // Filter out common non-chat elements
                val lower = text.lowercase()
                if (lower in listOf("chats", "calls", "status", "new group", "new broadcast",
                    "linked devices", "settings", "whatsapp", "search", "more options", "")) continue
                if (lower.startsWith("http") || lower.startsWith("@") || lower.matches(Regex("""[\d\s\-\(\)\+]{5,}"""))) continue
                // Check if it looks like a contact name (not a system element)
                if (!el.isClickable && !el.contentDescription.contains("double tap", true)) continue
                if (text.length < 3) continue
                if (!chatNames.contains(text)) {
                    chatNames.add(text)
                }
            }
        }
        return chatNames
    }

    // Get all visible text on screen (for reading)
    fun getAllVisibleText(): List<String> {
        val analysis = lastAnalysis ?: return emptyList()
        return analysis.textElements
            .filter { it.isVisible && it.text.length > 1 }
            .map { it.text.trim() }
            .distinct()
    }

    // Context-aware element finding: find the most relevant element for current action
    fun findRelevantElement(actionContext: String): ScreenElement? {
        val analysis = lastAnalysis ?: return null
        val lowerCtx = actionContext.lowercase()

        return when {
            lowerCtx.contains("send") || lowerCtx.contains("bhej") || lowerCtx.contains("message") -> {
                // When sending message, look for send button near the bottom
                analysis.clickableButtons.filter {
                    val txt = it.text.lowercase() + it.contentDescription.lowercase()
                    txt.contains("send") || txt.contains("bhej") || it.viewId.contains("send")
                }.maxByOrNull { it.centerY } // bottom-most send button
            }
            lowerCtx.contains("search") || lowerCtx.contains("dhoond") || lowerCtx.contains("khoj") -> {
                // When searching, find search bar
                analysis.editableFields.filter {
                    val txt = it.text.lowercase() + it.contentDescription.lowercase() + it.viewId.lowercase()
                    txt.contains("search") || txt.contains("dhoond")
                }.minByOrNull { it.centerY } // top-most search bar
            }
            lowerCtx.contains("type") || lowerCtx.contains("likh") || lowerCtx.contains("write") -> {
                // When typing, find editable field
                analysis.editableFields.maxByOrNull { it.centerY } // bottom-most editable field
            }
            lowerCtx.contains("back") || lowerCtx.contains("wapis") || lowerCtx.contains("peeche") -> {
                // When going back, find back button (usually top-left)
                analysis.clickableButtons.filter {
                    val txt = it.text.lowercase() + it.contentDescription.lowercase() + it.viewId.lowercase()
                    txt.contains("back") || txt.contains("wapis") || txt.contains("up") || txt.contains("close")
                }.minByOrNull { it.centerX + it.centerY } // top-left most
            }
            lowerCtx.contains("call") || lowerCtx.contains("phone") -> {
                analysis.clickableButtons.filter {
                    val txt = it.text.lowercase() + it.contentDescription.lowercase()
                    txt.contains("call") || txt.contains("phone") || txt.contains("audio")
                }.maxByOrNull { it.centerY }
            }
            lowerCtx.contains("video") && (lowerCtx.contains("call") || lowerCtx.contains("karo")) -> {
                analysis.clickableButtons.filter {
                    val txt = it.text.lowercase() + it.contentDescription.lowercase()
                    txt.contains("video") || txt.contains("camera")
                }.maxByOrNull { it.centerY }
            }
            lowerCtx.contains("attach") || lowerCtx.contains("file") || lowerCtx.contains("clip") -> {
                analysis.clickableButtons.filter {
                    val txt = it.text.lowercase() + it.contentDescription.lowercase() + it.viewId.lowercase()
                    txt.contains("attach") || txt.contains("clip") || txt.contains("file") || txt.contains("plus")
                }.minByOrNull { it.centerY }
            }
            lowerCtx.contains("camera") || lowerCtx.contains("photo") -> {
                analysis.clickableButtons.filter {
                    val txt = it.text.lowercase() + it.contentDescription.lowercase()
                    txt.contains("camera") || txt.contains("photo") || txt.contains("capture")
                }.maxByOrNull { it.centerY }
            }
            else -> null
        }
    }
}
