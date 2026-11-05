package com.jarvis.assistant.ui.chat

import android.content.Context
import android.graphics.Color as AndroidColor
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.R
import com.jarvis.assistant.androllm.attachments.ChatAttachmentJson
import com.jarvis.assistant.androllm.chat.model.Conversation
import com.jarvis.assistant.androllm.chat.model.Message
import com.jarvis.assistant.androllm.chat.model.MessageRole
import com.jarvis.assistant.databinding.ItemJarvisConvDrawerRowBinding
import com.jarvis.assistant.databinding.ItemJarvisConvMessageBinding
import com.jarvis.assistant.ui.compose.feature.chat.MarkdownRenderer
import com.jarvis.assistant.ui.compose.theme.AndroLLMTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Resolves a Jarvis theme attribute (?attr/theme*) to a color int. */
internal fun Context.jarvisThemeColor(attr: Int, fallback: Int): Int {
    val tv = TypedValue()
    if (!theme.resolveAttribute(attr, tv, true)) return fallback
    return if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
        tv.data
    } else if (tv.resourceId != 0) {
        runCatching { ContextCompat.getColor(this, tv.resourceId) }.getOrDefault(fallback)
    } else {
        fallback
    }
}

private val MARKDOWN_HINT = Regex(
    "(?m)(^\\s*```|^#{1,6}\\s|^\\s*[-*+]\\s+\\S|^\\s*\\d+[.)]\\s+\\S|^\\s*>\\s|\\|.+\\||\\*\\*[^*]+\\*\\*|`[^`]+`|\\[[^\\]]+]\\([^)]+\\)|https?://)"
)

/** Plain replies keep Jarvis' original TextView bubble; Markdown / links use the AndroLLM renderer. */
internal fun looksLikeMarkdown(text: String): Boolean = MARKDOWN_HINT.containsMatchIn(text)

/** One row of the Chat tab message list. */
internal sealed class ChatTabRow {
    abstract val key: String

    data class Item(
        val message: Message,
        val selected: Boolean,
        val isLastAssistant: Boolean,
        val showFollowUps: Boolean
    ) : ChatTabRow() {
        override val key: String get() = message.id
    }

    /** Jarvis is answering (live Gemini transcript when available). */
    data class Streaming(val text: String) : ChatTabRow() {
        override val key: String get() = "streaming"
    }
}

internal interface ChatTabMessageActions {
    fun onCopy(message: Message)
    fun onEdit(message: Message)
    fun onPlay(message: Message)
    fun onRegenerate(message: Message)
    fun onToggleBookmark(message: Message)
    fun onShare(message: Message)
    fun onMore(anchor: View, message: Message, isLastAssistant: Boolean)
    fun onLongPress(message: Message)

    /** Returns true when the tap was consumed (multi-select mode). */
    fun onTap(message: Message): Boolean
    fun onFollowUp(text: String)
}

/** Jarvis-styled message list for the Chat tab (bubbles from item_jarvis_conv_message.xml). */
internal class ChatTabMessageAdapter(
    private val actions: ChatTabMessageActions
) : ListAdapter<ChatTabRow, ChatTabMessageAdapter.Holder>(Diff) {

    private object Diff : DiffUtil.ItemCallback<ChatTabRow>() {
        override fun areItemsTheSame(oldItem: ChatTabRow, newItem: ChatTabRow) = oldItem.key == newItem.key
        override fun areContentsTheSame(oldItem: ChatTabRow, newItem: ChatTabRow) = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemJarvisConvMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val b: ItemJarvisConvMessageBinding) : RecyclerView.ViewHolder(b.root) {

        private val context = b.root.context
        private val markdown = mutableStateOf("")
        private val selectedBg = context.jarvisThemeColor(R.attr.themeChipBg, 0x22FFFFFF)
        private val accent = context.jarvisThemeColor(R.attr.themeAccent, AndroidColor.WHITE)
        private val muted = context.jarvisThemeColor(R.attr.themeTextMuted, AndroidColor.GRAY)
        private var followUpsBuilt = false

        init {
            val textColor = context.jarvisThemeColor(R.attr.themeTextPrimary, AndroidColor.WHITE)
            b.composeAssistantMarkdown.setContent {
                AndroLLMTheme {
                    MarkdownRenderer(markdownText = markdown.value, textColor = Color(textColor))
                }
            }
        }

        fun bind(row: ChatTabRow) {
            when (row) {
                is ChatTabRow.Streaming -> bindStreaming(row)
                is ChatTabRow.Item -> bindItem(row)
            }
        }

        private fun bindStreaming(row: ChatTabRow.Streaming) {
            b.layoutMessageRoot.setBackgroundColor(AndroidColor.TRANSPARENT)
            b.layoutUserContainer.visibility = View.GONE
            b.layoutAssistantContainer.visibility = View.VISIBLE
            b.composeAssistantMarkdown.visibility = View.GONE
            b.tvAssistantMessage.visibility = View.VISIBLE
            b.tvAssistantMessage.text = row.text.ifBlank { "Jarvis is replying…" }
            b.layoutAssistantActions.visibility = View.GONE
            b.scrollFollowUps.visibility = View.GONE
            b.tvAssistantMessage.setOnClickListener(null)
            b.tvAssistantMessage.setOnLongClickListener(null)
        }

        private fun bindItem(row: ChatTabRow.Item) {
            val message = row.message
            b.layoutMessageRoot.setBackgroundColor(if (row.selected) selectedBg else AndroidColor.TRANSPARENT)
            val tap = View.OnClickListener { actions.onTap(message) }
            val longPress = View.OnLongClickListener {
                actions.onLongPress(message)
                true
            }
            b.layoutMessageRoot.setOnClickListener(tap)
            b.layoutMessageRoot.setOnLongClickListener(longPress)

            if (message.role == MessageRole.USER) {
                b.layoutUserContainer.visibility = View.VISIBLE
                b.layoutAssistantContainer.visibility = View.GONE
                b.tvUserMessage.text = message.content.ifBlank { "(attachment)" }
                b.tvUserMessage.setOnClickListener(tap)
                b.tvUserMessage.setOnLongClickListener(longPress)
                val attachments = ChatAttachmentJson.decodeFromString(message.attachmentsJson)
                if (attachments.isEmpty()) {
                    b.tvUserAttachments.visibility = View.GONE
                } else {
                    b.tvUserAttachments.visibility = View.VISIBLE
                    b.tvUserAttachments.text = attachments.joinToString(", ") { att ->
                        if (att.fromOcr) "${att.name} (OCR)" else att.name
                    }
                }
                b.ivUserBookmarked.visibility = if (message.isBookmarked) View.VISIBLE else View.GONE
                b.btnUserEdit.setOnClickListener { actions.onEdit(message) }
                b.btnUserCopy.setOnClickListener { actions.onCopy(message) }
                b.btnUserMore.setOnClickListener { actions.onMore(it, message, false) }
                b.scrollFollowUps.visibility = View.GONE
                return
            }

            b.layoutUserContainer.visibility = View.GONE
            b.layoutAssistantContainer.visibility = View.VISIBLE
            b.layoutAssistantActions.visibility = View.VISIBLE
            b.layoutAssistantContainer.setOnClickListener(tap)
            b.layoutAssistantContainer.setOnLongClickListener(longPress)
            if (looksLikeMarkdown(message.content)) {
                b.tvAssistantMessage.visibility = View.GONE
                b.composeAssistantMarkdown.visibility = View.VISIBLE
                markdown.value = message.content
            } else {
                b.composeAssistantMarkdown.visibility = View.GONE
                b.tvAssistantMessage.visibility = View.VISIBLE
                b.tvAssistantMessage.text = message.content
                b.tvAssistantMessage.setOnClickListener(tap)
                b.tvAssistantMessage.setOnLongClickListener(longPress)
            }
            b.btnAssistantCopy.setOnClickListener { actions.onCopy(message) }
            b.btnAssistantPlay.setOnClickListener { actions.onPlay(message) }
            b.btnAssistantRegenerate.visibility = if (row.isLastAssistant) View.VISIBLE else View.GONE
            b.btnAssistantRegenerate.setOnClickListener { actions.onRegenerate(message) }
            b.btnAssistantBookmark.setImageResource(
                if (message.isBookmarked) R.drawable.ic_jchat_bookmark_filled else R.drawable.ic_jchat_bookmark
            )
            b.btnAssistantBookmark.setColorFilter(if (message.isBookmarked) accent else muted)
            b.btnAssistantBookmark.setOnClickListener { actions.onToggleBookmark(message) }
            b.btnAssistantShare.setOnClickListener { actions.onShare(message) }
            b.btnAssistantMore.setOnClickListener { actions.onMore(it, message, row.isLastAssistant) }
            b.tvAssistantMeta.text = buildString {
                append(TIME_FORMAT.get()!!.format(Date(message.timestamp)))
                if (message.isPending) append(" · pending")
            }

            if (row.showFollowUps) {
                if (!followUpsBuilt) buildFollowUps()
                b.scrollFollowUps.visibility = View.VISIBLE
            } else {
                b.scrollFollowUps.visibility = View.GONE
            }
        }

        private fun buildFollowUps() {
            followUpsBuilt = true
            val density = context.resources.displayMetrics.density
            val textColor = context.jarvisThemeColor(R.attr.themeTextPrimary, AndroidColor.WHITE)
            FOLLOW_UPS.forEach { suggestion ->
                val chip = TextView(context).apply {
                    text = suggestion
                    textSize = 12f
                    setTextColor(textColor)
                    setBackgroundResource(R.drawable.bg_set_chip) // 2026-11-05 redesign: outline pill
                    setPadding((12 * density).toInt(), (7 * density).toInt(), (12 * density).toInt(), (7 * density).toInt())
                    setOnClickListener { actions.onFollowUp(suggestion) }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * density).toInt() }
                b.layoutFollowUps.addView(chip, lp)
            }
        }
    }

    companion object {
        /** AndroLLM smart-reply follow-ups. */
        val FOLLOW_UPS = listOf(
            "Explain this in more detail",
            "Give me a concrete example",
            "Summarise the key points",
            "What are the trade-offs?"
        )

        private val TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("HH:mm", Locale.getDefault())
        }
    }
}

// ── Conversation drawer ──────────────────────────────────────────────────────

internal sealed class ChatDrawerRow {
    abstract val key: String

    data class Section(val label: String) : ChatDrawerRow() {
        override val key: String get() = "section:$label"
    }

    data class Conv(val conversation: Conversation, val isActive: Boolean) : ChatDrawerRow() {
        override val key: String get() = "conv:${conversation.id}"
    }

    data class Hit(val message: Message, val conversationTitle: String, val query: String) : ChatDrawerRow() {
        override val key: String get() = "hit:${message.id}"
    }
}

internal class ChatDrawerAdapter(
    private val onOpenConversation: (Conversation) -> Unit,
    private val onConversationMore: (View, Conversation) -> Unit,
    private val onOpenHit: (Message) -> Unit
) : ListAdapter<ChatDrawerRow, ChatDrawerAdapter.Holder>(Diff) {

    private object Diff : DiffUtil.ItemCallback<ChatDrawerRow>() {
        override fun areItemsTheSame(oldItem: ChatDrawerRow, newItem: ChatDrawerRow) = oldItem.key == newItem.key
        override fun areContentsTheSame(oldItem: ChatDrawerRow, newItem: ChatDrawerRow) = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemJarvisConvDrawerRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val b: ItemJarvisConvDrawerRowBinding) : RecyclerView.ViewHolder(b.root) {
        private val context = b.root.context
        private val primary = context.jarvisThemeColor(R.attr.themeTextPrimary, AndroidColor.WHITE)
        private val accent = context.jarvisThemeColor(R.attr.themeAccent, AndroidColor.WHITE)

        fun bind(row: ChatDrawerRow) {
            when (row) {
                is ChatDrawerRow.Section -> {
                    b.tvDrawerSection.visibility = View.VISIBLE
                    b.tvDrawerSection.text = row.label
                    b.layoutDrawerItem.visibility = View.GONE
                }
                is ChatDrawerRow.Conv -> {
                    val c = row.conversation
                    b.tvDrawerSection.visibility = View.GONE
                    b.layoutDrawerItem.visibility = View.VISIBLE
                    b.tvDrawerItemTitle.text = c.title
                    b.tvDrawerItemTitle.setTextColor(if (row.isActive) accent else primary)
                    b.tvDrawerItemSubtitle.text = buildString {
                        val preview = c.lastMessagePreview?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                        if (preview.isNotEmpty()) append(preview.take(80)).append(" · ")
                        append(c.messageCount).append(if (c.messageCount == 1) " message" else " messages")
                    }
                    b.ivDrawerItemPin.visibility = if (c.isPinned) View.VISIBLE else View.GONE
                    b.btnDrawerItemMore.visibility = View.VISIBLE
                    b.btnDrawerItemMore.setOnClickListener { onConversationMore(it, c) }
                    b.layoutDrawerItem.setOnClickListener { onOpenConversation(c) }
                    b.layoutDrawerItem.setOnLongClickListener {
                        onConversationMore(b.btnDrawerItemMore, c)
                        true
                    }
                }
                is ChatDrawerRow.Hit -> {
                    b.tvDrawerSection.visibility = View.GONE
                    b.layoutDrawerItem.visibility = View.VISIBLE
                    b.tvDrawerItemTitle.text = row.conversationTitle
                    b.tvDrawerItemTitle.setTextColor(primary)
                    val who = if (row.message.role == MessageRole.USER) "You" else "Jarvis"
                    b.tvDrawerItemSubtitle.text = "$who: ${snippet(row.message.content, row.query)}"
                    b.ivDrawerItemPin.visibility = View.GONE
                    b.btnDrawerItemMore.visibility = View.GONE
                    b.layoutDrawerItem.setOnClickListener { onOpenHit(row.message) }
                    b.layoutDrawerItem.setOnLongClickListener(null)
                }
            }
        }

        private fun snippet(text: String, query: String): String {
            val flat = text.replace(Regex("\\s+"), " ").trim()
            if (query.isBlank()) return flat.take(120)
            val index = flat.indexOf(query, ignoreCase = true)
            if (index < 0) return flat.take(120)
            val start = (index - 40).coerceAtLeast(0)
            val end = (index + query.length + 80).coerceAtMost(flat.length)
            return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
        }
    }
}
