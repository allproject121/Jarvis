package com.jarvis.assistant.connectors

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object N8nWorkflowGenerator {

    /**
     * Generates a valid n8n workflow JSON structure matching standard n8n-nodes-base specs.
     */
    fun generateWorkflow(workflowName: String, triggerType: String, description: String): String {
        val root = JSONObject()
        root.put("name", workflowName)
        root.put("active", false)
        root.put("settings", JSONObject())

        val nodes = JSONArray()
        val connections = JSONObject()

        // 1. Trigger Node
        val triggerId = UUID.randomUUID().toString()
        val triggerNodeName = when (triggerType.lowercase()) {
            "schedule" -> "Schedule Trigger"
            "form" -> "Form Trigger"
            else -> "Webhook"
        }
        val triggerNodeType = when (triggerType.lowercase()) {
            "schedule" -> "n8n-nodes-base.scheduleTrigger"
            "form" -> "n8n-nodes-base.formTrigger"
            else -> "n8n-nodes-base.webhook"
        }

        val triggerNode = JSONObject().apply {
            put("id", triggerId)
            put("name", triggerNodeName)
            put("type", triggerNodeType)
            put("typeVersion", 1)
            put("position", JSONArray(listOf(200, 300)))
            put("parameters", JSONObject().apply {
                if (triggerNodeType.contains("webhook")) {
                    put("path", workflowName.lowercase().replace(" ", "-"))
                    put("httpMethod", "POST")
                }
            })
        }
        nodes.put(triggerNode)

        // 2. AI Processing Node
        val aiId = UUID.randomUUID().toString()
        val aiNodeName = "AI Agent"
        val aiNode = JSONObject().apply {
            put("id", aiId)
            put("name", aiNodeName)
            put("type", "n8n-nodes-base.openAi")
            put("typeVersion", 1)
            put("position", JSONArray(listOf(440, 300)))
            put("parameters", JSONObject().apply {
                put("resource", "chat")
                put("prompt", description)
            })
        }
        nodes.put(aiNode)

        // 3. Notification / Delivery Node (Telegram / Slack)
        val notifId = UUID.randomUUID().toString()
        val notifNodeName = "Send Telegram Alert"
        val notifNode = JSONObject().apply {
            put("id", notifId)
            put("name", notifNodeName)
            put("type", "n8n-nodes-base.telegram")
            put("typeVersion", 1)
            put("position", JSONArray(listOf(680, 300)))
            put("parameters", JSONObject().apply {
                put("operation", "sendMessage")
            })
        }
        nodes.put(notifNode)

        // Connections
        // Trigger -> AI
        val triggerConns = JSONObject().apply {
            val mainConns = JSONArray().apply {
                put(JSONArray().apply {
                    put(JSONObject().apply {
                        put("node", aiNodeName)
                        put("type", "main")
                        put("index", 0)
                    })
                })
            }
            put("main", mainConns)
        }
        connections.put(triggerNodeName, triggerConns)

        // AI -> Telegram
        val aiConns = JSONObject().apply {
            val mainConns = JSONArray().apply {
                put(JSONArray().apply {
                    put(JSONObject().apply {
                        put("node", notifNodeName)
                        put("type", "main")
                        put("index", 0)
                    })
                })
            }
            put("main", mainConns)
        }
        connections.put(aiNodeName, aiConns)

        root.put("nodes", nodes)
        root.put("connections", connections)

        return root.toString(2)
    }
}
