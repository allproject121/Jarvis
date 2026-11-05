package com.jarvis.assistant.automation.workflow

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * n8n Automation Workflow Graph Generator.
 * Generates production-ready, importable n8n workflow JSON graphs for automated
 * multi-step integrations (Webhooks, Scheduled Triggers, WhatsApp, Telegram, Sheets).
 */
class N8nWorkflowGenerator(private val context: Context) {

    companion object {
        private const val TAG = "N8nWorkflowGenerator"
    }

    data class WorkflowResult(
        val success: Boolean,
        val filePath: String,
        val workflowJson: String,
        val message: String
    )

    fun generateWorkflow(
        workflowName: String,
        triggerType: String = "webhook", // webhook, schedule, manual
        actionType: String = "whatsapp", // whatsapp, slack, telegram, sheets, http
        destinationEndpoint: String = ""
    ): WorkflowResult {
        return try {
            val workflowId = UUID.randomUUID().toString().take(12)
            val cleanName = workflowName.ifBlank { "Jarvis Automation Flow $workflowId" }
            val root = JSONObject()
            root.put("name", cleanName)
            root.put("active", false)

            val nodes = JSONArray()
            val connections = JSONObject()

            // 1. Trigger Node
            val triggerNode = JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("position", JSONArray().put(240).put(300))
                when (triggerType.lowercase()) {
                    "schedule", "cron" -> {
                        put("name", "Schedule Trigger")
                        put("type", "n8n-nodes-base.scheduleTrigger")
                        put("typeVersion", 1.2)
                        put("parameters", JSONObject().apply {
                            put("rule", JSONObject().apply {
                                put("interval", JSONArray().put(JSONObject().apply {
                                    put("field", "hours")
                                    put("hoursInterval", 24)
                                }))
                            })
                        })
                    }
                    else -> {
                        put("name", "Webhook Trigger")
                        put("type", "n8n-nodes-base.webhook")
                        put("typeVersion", 2)
                        put("parameters", JSONObject().apply {
                            put("httpMethod", "POST")
                            put("path", "jarvis-hook-${UUID.randomUUID().toString().take(6)}")
                            put("responseMode", "onReceived")
                        })
                    }
                }
            }
            nodes.put(triggerNode)

            // 2. Transformer / Code Node
            val transformNode = JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("name", "Format Payload")
                put("type", "n8n-nodes-base.code")
                put("typeVersion", 2)
                put("position", JSONArray().put(480).put(300))
                put("parameters", JSONObject().apply {
                    put("language", "javaScript")
                    put("jsCode", """
                        // JARVIS Payload Transformer
                        const items = ${'$'}input.all();
                        return items.map(item => {
                            const data = item.json;
                            return {
                                json: {
                                    processed_at: new Date().toISOString(),
                                    title: data.title || 'Jarvis Automation Alert',
                                    message: data.message || 'Triggered from mobile assistant',
                                    sender: data.sender || 'Jarvis AI Core',
                                    raw: data
                                }
                            };
                        });
                    """.trimIndent())
                })
            }
            nodes.put(transformNode)

            // 3. Action Destination Node
            val actionNode = JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("position", JSONArray().put(720).put(300))
                when (actionType.lowercase()) {
                    "slack" -> {
                        put("name", "Slack Notification")
                        put("type", "n8n-nodes-base.slack")
                        put("typeVersion", 2.2)
                        put("parameters", JSONObject().apply {
                            put("resource", "message")
                            put("operation", "post")
                            put("channel", destinationEndpoint.ifBlank { "#alerts" })
                            put("text", "={{ ${'$'}json.message }}")
                        })
                    }
                    "telegram" -> {
                        put("name", "Telegram Alert")
                        put("type", "n8n-nodes-base.telegram")
                        put("typeVersion", 1.2)
                        put("parameters", JSONObject().apply {
                            put("operation", "sendMessage")
                            put("chatId", destinationEndpoint.ifBlank { "YOUR_CHAT_ID" })
                            put("text", "={{ ${'$'}json.message }}")
                        })
                    }
                    "sheets" -> {
                        put("name", "Google Sheets Append")
                        put("type", "n8n-nodes-base.googleSheets")
                        put("typeVersion", 4.5)
                        put("parameters", JSONObject().apply {
                            put("operation", "append")
                            put("documentId", destinationEndpoint.ifBlank { "SPREADSHEET_ID" })
                            put("sheetName", "Sheet1")
                        })
                    }
                    else -> {
                        // Default to Generic HTTP / WhatsApp Webhook
                        put("name", "Dispatch Action")
                        put("type", "n8n-nodes-base.httpRequest")
                        put("typeVersion", 4.2)
                        put("parameters", JSONObject().apply {
                            put("method", "POST")
                            put("url", destinationEndpoint.ifBlank { "https://api.whatsapp.com/v1/messages" })
                            put("sendBody", true)
                            put("specifyBody", "json")
                            put("jsonBody", "={{ JSON.stringify(${'$'}json) }}")
                        })
                    }
                }
            }
            nodes.put(actionNode)

            // Connect nodes: Trigger -> Transform -> Action
            val triggerName = triggerNode.getString("name")
            val transformName = transformNode.getString("name")
            val actionName = actionNode.getString("name")

            connections.put(triggerName, JSONObject().apply {
                put("main", JSONArray().put(JSONArray().put(JSONObject().apply {
                    put("node", transformName)
                    put("type", "main")
                    put("index", 0)
                })))
            })

            connections.put(transformName, JSONObject().apply {
                put("main", JSONArray().put(JSONArray().put(JSONObject().apply {
                    put("node", actionName)
                    put("type", "main")
                    put("index", 0)
                })))
            })

            root.put("nodes", nodes)
            root.put("connections", connections)
            root.put("settings", JSONObject().apply {
                put("executionOrder", "v1")
            })

            val jsonString = root.toString(2)

            // Save to Downloads folder
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val jarvisWorkflowsDir = File(downloadsDir, "JarvisWorkflows").apply { mkdirs() }
            val slug = cleanName.replace(Regex("[^a-zA-Z0-9_-]"), "_").lowercase()
            val file = File(jarvisWorkflowsDir, "n8n_${slug}_$workflowId.json")
            file.writeText(jsonString)

            Log.i(TAG, "Generated n8n workflow at: ${file.absolutePath}")
            WorkflowResult(
                success = true,
                filePath = file.absolutePath,
                workflowJson = jsonString,
                message = "Successfully created n8n workflow graph for '$cleanName'. Saved to: ${file.name}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed generating n8n workflow: ${e.message}", e)
            WorkflowResult(
                success = false,
                filePath = "",
                workflowJson = "",
                message = "Workflow generation error: ${e.message}"
            )
        }
    }
}
