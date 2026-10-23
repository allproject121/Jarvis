package com.jarvis.assistant.ui.diff

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.databinding.ActivityProposalDiffBinding

/**
 * On-device visual Diff Review Screen for Jarvis Skill Builder Integration Proposals.
 * Displays syntax-highlighted multi-file unified diffs with file counters,
 * status badges, clipboard export, and security guardrail indicators.
 */
class ProposalDiffActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROPOSAL_ID = "extra_proposal_id"
        const val EXTRA_FEATURE_NAME = "extra_feature_name"
        const val EXTRA_DIFF = "extra_diff"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_TARGET_FILE = "extra_target_file"

        // Syntax highlighting palette
        private const val COLOR_ADDITION = 0xFF4ADE80.toInt()      // Green
        private const val COLOR_DELETION = 0xFFF87171.toInt()      // Red
        private const val COLOR_CHUNK_HEADER = 0xFF38BDF8.toInt()  // Sky Blue
        private const val COLOR_FILE_HEADER = 0xFFF1F5F9.toInt()   // Crisp White
        private const val COLOR_CONTEXT = 0xFF94A3B8.toInt()       // Slate Gray
    }

    private lateinit var binding: ActivityProposalDiffBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProposalDiffBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val proposalId = intent.getStringExtra(EXTRA_PROPOSAL_ID) ?: "Unknown Proposal"
        val featureName = intent.getStringExtra(EXTRA_FEATURE_NAME) ?: "Requested Feature"
        val diffText = intent.getStringExtra(EXTRA_DIFF) ?: ""
        val status = intent.getStringExtra(EXTRA_STATUS) ?: "PENDING_USER_APPROVAL"

        setupUI(proposalId, featureName, diffText, status)
    }

    private fun setupUI(
        proposalId: String,
        featureName: String,
        diffText: String,
        status: String
    ) {
        binding.tvProposalTitle.text = featureName
        binding.tvProposalSubtitle.text = "Proposal: $proposalId"
        binding.tvStatusBadge.text = status

        val lines = diffText.lines()
        val totalLines = lines.size
        val fileCount = lines.count { it.startsWith("+++ b/") || it.startsWith("--- a/") } / 2
        val actualFiles = if (fileCount > 0) fileCount else if (diffText.isNotBlank()) 1 else 0
        binding.tvFileStats.text = "$actualFiles files • $totalLines lines • ${diffText.length} chars"

        // Highlight diff content
        binding.tvDiffContent.text = formatDiffSpannable(diffText)

        // Close / Back button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Copy button
        binding.btnCopyDiff.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Proposal Diff $proposalId", diffText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Complete diff copied to clipboard (${diffText.length} chars)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatDiffSpannable(diff: String): CharSequence {
        if (diff.isBlank()) {
            return SpannableStringBuilder("(Empty diff content)")
        }

        val builder = SpannableStringBuilder()
        val lines = diff.lines()

        for (i in lines.indices) {
            val line = lines[i]
            val start = builder.length
            builder.append(line)
            val end = builder.length

            when {
                line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff --git") -> {
                    builder.setSpan(ForegroundColorSpan(COLOR_FILE_HEADER), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.startsWith("+") -> {
                    builder.setSpan(ForegroundColorSpan(COLOR_ADDITION), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.startsWith("-") -> {
                    builder.setSpan(ForegroundColorSpan(COLOR_DELETION), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.startsWith("@@") -> {
                    builder.setSpan(ForegroundColorSpan(COLOR_CHUNK_HEADER), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    builder.setSpan(ForegroundColorSpan(COLOR_CONTEXT), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            if (i < lines.size - 1) {
                builder.append("\n")
            }
        }

        return builder
    }
}
