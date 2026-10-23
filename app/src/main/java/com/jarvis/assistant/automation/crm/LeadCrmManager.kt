package com.jarvis.assistant.automation.crm

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Customer Lead Management & CRM Engine.
 * Provides on-device CRM storage, lead qualification, deal stage tracking,
 * and search capabilities for sales and customer relations.
 */
class LeadCrmManager private constructor(private val context: Context) {

    data class LeadRecord(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val phone: String,
        val company: String? = null,
        var status: String = "NEW", // NEW, CONTACTED, FOLLOW_UP_SCHEDULED, CONVERTED, LOST
        var notes: String = "",
        var dealValue: Double = 0.0,
        val createdAt: Long = System.currentTimeMillis(),
        var updatedAt: Long = System.currentTimeMillis()
    )

    companion object {
        private const val TAG = "LeadCrmManager"
        private const val CRM_FILE_NAME = "crm_leads.json"

        @Volatile
        private var instance: LeadCrmManager? = null

        fun getInstance(context: Context): LeadCrmManager {
            return instance ?: synchronized(this) {
                instance ?: LeadCrmManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val gson = Gson()
    private val leadsFile: File get() = File(context.filesDir, CRM_FILE_NAME)
    private val memoryLock = Any()

    @Synchronized
    private fun loadLeads(): MutableList<LeadRecord> {
        synchronized(memoryLock) {
            return try {
                if (!leadsFile.exists()) return mutableListOf()
                val json = leadsFile.readText()
                val type = object : TypeToken<MutableList<LeadRecord>>() {}.type
                gson.fromJson<MutableList<LeadRecord>>(json, type) ?: mutableListOf()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading CRM leads: ${e.message}")
                mutableListOf()
            }
        }
    }

    @Synchronized
    private fun saveLeads(leads: List<LeadRecord>) {
        synchronized(memoryLock) {
            try {
                val json = gson.toJson(leads)
                leadsFile.writeText(json)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving CRM leads: ${e.message}")
            }
        }
    }

    fun addLead(
        name: String,
        phone: String,
        company: String? = null,
        notes: String = "",
        dealValue: Double = 0.0,
        initialStatus: String = "NEW"
    ): LeadRecord {
        val leads = loadLeads()
        val existingIndex = leads.indexOfFirst {
            it.phone.filter { ch -> ch.isDigit() } == phone.filter { ch -> ch.isDigit() }
        }

        val record = if (existingIndex >= 0) {
            val existing = leads[existingIndex]
            val updated = existing.copy(
                status = initialStatus,
                notes = if (notes.isNotBlank()) "${existing.notes} | $notes".trim() else existing.notes,
                dealValue = if (dealValue > 0.0) dealValue else existing.dealValue,
                updatedAt = System.currentTimeMillis()
            )
            leads[existingIndex] = updated
            updated
        } else {
            val newLead = LeadRecord(
                name = name,
                phone = phone,
                company = company,
                status = initialStatus,
                notes = notes,
                dealValue = dealValue
            )
            leads.add(0, newLead)
            newLead
        }

        saveLeads(leads)
        Log.i(TAG, "Added/Updated lead: ${record.name} ($phone) [${record.status}]")
        return record
    }

    fun updateLeadStatus(phoneOrId: String, newStatus: String, notes: String? = null): Boolean {
        val leads = loadLeads()
        val cleanQuery = phoneOrId.filter { it.isDigit() }
        val index = leads.indexOfFirst {
            it.id.equals(phoneOrId, ignoreCase = true) ||
            (cleanQuery.isNotEmpty() && it.phone.filter { ch -> ch.isDigit() }.endsWith(cleanQuery))
        }

        if (index >= 0) {
            val existing = leads[index]
            existing.status = newStatus.uppercase()
            existing.updatedAt = System.currentTimeMillis()
            if (!notes.isNullOrBlank()) {
                existing.notes = "${existing.notes} | $notes".trim()
            }
            saveLeads(leads)
            Log.i(TAG, "Updated lead ${existing.name} to status $newStatus")
            return true
        }
        return false
    }

    fun listLeads(statusFilter: String? = null, limit: Int = 20): List<LeadRecord> {
        val leads = loadLeads()
        return if (!statusFilter.isNullOrBlank() && statusFilter != "ALL") {
            leads.filter { it.status.equals(statusFilter, ignoreCase = true) }.take(limit)
        } else {
            leads.take(limit)
        }
    }

    fun searchLeads(query: String): List<LeadRecord> {
        val leads = loadLeads()
        val q = query.lowercase().trim()
        val cleanDigits = query.filter { it.isDigit() }
        return leads.filter { lead ->
            lead.name.lowercase().contains(q) ||
            lead.company?.lowercase()?.contains(q) == true ||
            lead.notes.lowercase().contains(q) ||
            (cleanDigits.isNotEmpty() && lead.phone.filter { it.isDigit() }.contains(cleanDigits))
        }
    }

    fun deleteLead(phoneOrId: String): Boolean {
        val leads = loadLeads()
        val cleanQuery = phoneOrId.filter { it.isDigit() }
        val removed = leads.removeAll {
            it.id.equals(phoneOrId, ignoreCase = true) ||
            (cleanQuery.isNotEmpty() && it.phone.filter { ch -> ch.isDigit() }.endsWith(cleanQuery))
        }
        if (removed) {
            saveLeads(leads)
            Log.i(TAG, "Deleted lead matching: $phoneOrId")
        }
        return removed
    }

    fun exportCsv(): String {
        val leads = loadLeads()
        val sb = java.lang.StringBuilder()
        sb.append("ID,Name,Phone,Company,Status,DealValue,Notes,CreatedAt\n")
        leads.forEach { lead ->
            sb.append("\"${lead.id}\",\"${lead.name}\",\"${lead.phone}\",\"${lead.company.orEmpty()}\",\"${lead.status}\",${lead.dealValue},\"${lead.notes.replace("\"", "\"\"")}\",${lead.createdAt}\n")
        }
        return sb.toString()
    }
}
