@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.jarvis.assistant.maya.business

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.automation.crm.LeadCrmManager
import com.jarvis.assistant.automation.crm.LeadFollowupScheduler
import com.jarvis.assistant.ui.compose.theme.AndroLLMTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Maya's business board inside Jarvis: Leads · Content · Calendar · Marketing · Finance · Missions.
 * Everything here mirrors the JSON stores the tools write, so voice and screen stay in sync.
 */
class BusinessActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TAB = "business_tab"
        const val TAB_LEADS = "leads"
        const val TAB_CONTENT = "content"
        const val TAB_CALENDAR = "calendar"
        const val TAB_MARKETING = "marketing"
        const val TAB_FINANCE = "finance"
        const val TAB_MISSIONS = "missions"
        val TABS = listOf(TAB_LEADS, TAB_CONTENT, TAB_CALENDAR, TAB_MARKETING, TAB_FINANCE, TAB_MISSIONS)
        val TAB_LABELS = listOf("Leads", "Content", "Calendar", "Marketing", "Finance", "Missions")

        fun intent(context: Context, tab: String? = null): Intent =
            Intent(context, BusinessActivity::class.java).apply { if (tab != null) putExtra(EXTRA_TAB, tab) }
    }

    private var initialTab by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialTab = tabIndex(intent)
        setContent { BusinessThemed { BusinessScreen(initialTab) { finish() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        initialTab = tabIndex(intent)
    }

    private fun tabIndex(i: Intent?): Int = TABS.indexOf(i?.getStringExtra(EXTRA_TAB)?.lowercase()).coerceAtLeast(0)
}

@Composable
private fun BusinessThemed(content: @Composable () -> Unit) {
    // Jarvis' own Compose theme (AndroLLMTheme follows the Jarvis theme key / dark blackout).
    AndroLLMTheme { Surface(color = MaterialTheme.colorScheme.background) { content() } }
}

/** Snapshot of everything the board shows; loaded off the main thread. */
private data class BoardData(
    val leads: List<LeadCrmManager.LeadRecord> = emptyList(),
    val content: List<ContentItem> = emptyList(),
    val boards: List<BoardRecord> = emptyList(),
    val missions: List<Mission> = emptyList(),
    val alerts: List<PriceAlert> = emptyList(),
    val holdings: List<Holding> = emptyList(),
    val watchlist: List<String> = emptyList(),
    val profile: BusinessProfile = BusinessProfile(),
    val kpi: String = ""
)

private fun loadData(ctx: Context): BoardData = BoardData(
    leads = runCatching { LeadCrmManager.getInstance(ctx).listLeads(null, 500) }.getOrDefault(emptyList()),
    content = ContentStore(ctx).all(),
    boards = BoardStore.all(ctx),
    missions = MissionRunner.all(ctx),
    alerts = FinanceStore.alerts(ctx),
    holdings = FinanceStore.holdings(ctx),
    watchlist = runCatching { FinanceEngine.client(ctx).getWatchlist().toList().sorted() }.getOrDefault(emptyList()),
    profile = BusinessProfileStore(ctx).load(),
    kpi = KpiStore.summary(ctx, "week")
)

@Composable
private fun BusinessScreen(initialTab: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember(initialTab) { mutableIntStateOf(initialTab) }
    var data by remember { mutableStateOf(BoardData()) }
    var tick by remember { mutableIntStateOf(0) }
    val refresh: () -> Unit = { tick++ }

    LaunchedEffect(tick) { data = withContext(Dispatchers.IO) { loadData(ctx.applicationContext) } }
    // Light polling so running missions / research show live progress.
    LaunchedEffect(Unit) {
        while (true) {
            delay(4000)
            data = withContext(Dispatchers.IO) { loadData(ctx.applicationContext) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(data.profile.name.ifBlank { "Business" }) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                BusinessActivity.TAB_LABELS.forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
            when (tab) {
                0 -> LeadsTab(data.leads, refresh)
                1 -> ContentTab(data.content.filter { it.status != ContentItem.STATUS_PUBLISHED || it.publishedAt > System.currentTimeMillis() - 3L * 86400000 }, refresh)
                2 -> CalendarTab(data.content, refresh)
                3 -> MarketingTab(data, refresh)
                4 -> FinanceTab(data, refresh)
                else -> MissionsTab(data.missions, refresh)
            }
        }
    }
}

// ------------------------------------------------------------------ shared bits

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
}

@Composable
private fun ItemCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).let { if (onClick != null) it.clickable { onClick() } else it },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) { Column(Modifier.padding(12.dp)) { content() } }
}

/** Renders "# / ## / - " board-script text. */
@Composable
private fun BoardText(text: String) {
    val board = remember(text) { BoardScriptParser.parse(text) }
    if (board.isEmpty()) { Text(text); return }
    Column {
        if (board.title.isNotBlank()) Text(board.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        board.sections.forEach { s ->
            Text(s.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
            s.points.forEach { p -> Text("•  $p", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)) }
        }
    }
}

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

private fun pickDateTime(ctx: Context, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }
    DatePickerDialog(ctx, { _, y, m, d ->
        TimePickerDialog(ctx, { _, h, min ->
            val c = Calendar.getInstance().apply { set(y, m, d, h, min, 0); set(Calendar.MILLISECOND, 0) }
            onPicked(c.timeInMillis)
        }, cal.get(Calendar.HOUR_OF_DAY), 0, true).show()
    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
}

// ------------------------------------------------------------------ Leads

private val STAGES = listOf("NEW", "CONTACTED", "FOLLOW_UP_SCHEDULED", "CONVERTED", "LOST")

@Composable
private fun LeadsTab(leads: List<LeadCrmManager.LeadRecord>, refresh: () -> Unit) {
    val ctx = LocalContext.current
    var filter by remember { mutableStateOf("ALL") }
    var expanded by remember { mutableStateOf<String?>(null) }
    val shown = leads.filter { filter == "ALL" || it.status.equals(filter, true) }
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf("ALL") + STAGES).forEach { s ->
                    val n = if (s == "ALL") leads.size else leads.count { it.status.equals(s, true) }
                    FilterChip(selected = filter == s, onClick = { filter = s }, label = { Text("${s.replace('_', ' ').lowercase()} ($n)") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Leads - ${shown.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val csv = LeadCrmManager.getInstance(ctx).exportCsv()
                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/csv"; putExtra(Intent.EXTRA_TEXT, csv); putExtra(Intent.EXTRA_SUBJECT, "leads.csv") }
                    ctx.startActivity(Intent.createChooser(send, "Export leads"))
                }) { Text("Export CSV") }
            }
        }
        if (shown.isEmpty()) item { EmptyNote("Koi lead nahi. Voice se bolo 'naya lead add karo' ya lead_generation chalao.") }
        items(shown, key = { it.id }) { lead ->
            ItemCard(onClick = { expanded = if (expanded == lead.id) null else lead.id }) {
                Text(lead.name, fontWeight = FontWeight.SemiBold)
                Text("${lead.phone}${lead.company?.let { " · $it" } ?: ""} · ${lead.status}", style = MaterialTheme.typography.bodySmall)
                if (lead.dealValue > 0) Text("Deal: ${KpiStore.fmt(lead.dealValue)}", style = MaterialTheme.typography.bodySmall)
                if (expanded == lead.id) {
                    if (lead.notes.isNotBlank()) Text(lead.notes, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    val drips = remember(lead.id) { LeadFollowupScheduler.getInstance(ctx).pendingDrips(lead.phone) }
                    drips.forEach { Text("Drip day ${it.day}: ${DateTimeParse.formatDateTime(it.triggerAt)}", style = MaterialTheme.typography.bodySmall) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        STAGES.forEach { s ->
                            FilterChip(selected = lead.status.equals(s, true), onClick = {
                                LeadCrmManager.getInstance(ctx).updateLeadStatus(lead.id, s); refresh()
                            }, label = { Text(s.replace('_', ' ').lowercase(), fontSize = 11.sp) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            pickDateTime(ctx) { at ->
                                LeadFollowupScheduler.getInstance(ctx).scheduleFollowupAt(lead.phone, lead.name, "Follow up", at)
                                toast(ctx, "Follow-up ${DateTimeParse.formatDateTime(at)}"); refresh()
                            }
                        }) { Text("Follow-up") }
                        OutlinedButton(onClick = {
                            SocialShare.openWhatsAppChat(ctx, lead.phone, "Namaste ${lead.name} ji, ")
                        }) { Text("WhatsApp") }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Content

@Composable
private fun ContentTab(items: List<ContentItem>, refresh: () -> Unit) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        item {
            Text(BusinessRuntime.SUGGESTION_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (items.isEmpty()) item { EmptyNote("Koi content nahi. Bolo 'ek reel script banao' - yahan Draft me aayega.") }
        items(items, key = { it.id }) { c ->
            ItemCard(onClick = { expanded = if (expanded == c.id) null else c.id }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(c.status, color = statusColor(c.status), style = MaterialTheme.typography.labelMedium)
                }
                Text("${c.kind} · ${c.platform}${if (c.pillar.isNotBlank()) " · ${c.pillar}" else ""}", style = MaterialTheme.typography.bodySmall)
                if (c.hook.isNotBlank()) Text(c.hook, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded == c.id) 20 else 2, overflow = TextOverflow.Ellipsis)
                if (expanded == c.id) {
                    Spacer(Modifier.height(6.dp))
                    Text(c.render(), style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (c.status == ContentItem.STATUS_DRAFT) OutlinedButton(onClick = { ContentEngine.approve(ctx, c.id); refresh() }) { Text("Approve") }
                    if (c.status != ContentItem.STATUS_DRAFT && c.status != ContentItem.STATUS_PUBLISHED) {
                        OutlinedButton(onClick = {
                            pickDateTime(ctx) { at -> ContentEngine.schedule(ctx, c.id, at); toast(ctx, "Scheduled ${DateTimeParse.formatDateTime(at)} - us waqt notify karungi"); refresh() }
                        }) { Text("Schedule") }
                        OutlinedButton(onClick = { val (_, msg) = ContentEngine.publish(ctx, c.id); toast(ctx, msg.take(160)) }) { Text("Publish") }
                    }
                    TextButton(onClick = { PostReminderReceiver.disarm(ctx, c); ContentStore(ctx).delete(c.id); refresh() }) { Text("Delete") }
                }
                if (c.status == ContentItem.STATUS_APPROVED || c.status == ContentItem.STATUS_SCHEDULED) {
                    TextButton(onClick = { ContentEngine.markPublished(ctx, c.id); refresh() }) { Text("Ye post ho gaya") }
                }
            }
        }
    }
}

@Composable
private fun statusColor(s: String): Color = when (s) {
    ContentItem.STATUS_DRAFT -> MaterialTheme.colorScheme.onSurfaceVariant
    ContentItem.STATUS_APPROVED -> Color(0xFF7FB3FF)
    ContentItem.STATUS_SCHEDULED -> Color(0xFFFFC66E)
    else -> Color(0xFF7BD88F)
}

// ------------------------------------------------------------------ Calendar

@Composable
private fun CalendarTab(all: List<ContentItem>, refresh: () -> Unit) {
    val ctx = LocalContext.current
    val dated = all.filter { it.scheduledAt > 0 || it.publishedAt > 0 }
        .sortedBy { if (it.scheduledAt > 0) it.scheduledAt else it.publishedAt }
        .groupBy { DateTimeParse.formatDate(if (it.scheduledAt > 0) it.scheduledAt else it.publishedAt) }
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        if (dated.isEmpty()) item { EmptyNote("Calendar khaali hai. Approved content ko schedule karo - wo yahan date-wise dikhega.") }
        dated.forEach { (date, list) ->
            item(key = "d-$date") { SectionTitle(date) }
            items(list, key = { "c-" + it.id }) { c ->
                ItemCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, fontWeight = FontWeight.SemiBold)
                            Text("${DateTimeParse.formatDateTime(if (c.scheduledAt > 0) c.scheduledAt else c.publishedAt)} · ${c.platform} · ${c.kind}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(c.status, color = statusColor(c.status), style = MaterialTheme.typography.labelMedium)
                    }
                    if (c.status == ContentItem.STATUS_SCHEDULED) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { val (_, m) = ContentEngine.publish(ctx, c.id); toast(ctx, m.take(160)) }) { Text("Publish now") }
                        TextButton(onClick = { ContentEngine.unschedule(ctx, c.id); refresh() }) { Text("Unschedule") }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Marketing

@Composable
private fun MarketingTab(data: BoardData, refresh: () -> Unit) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf<String?>(null) }
    var editProfile by remember { mutableStateOf(false) }
    val boards = data.boards.filter { BoardRecord.groupOf(it.kind) != "market" }
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        item {
            ItemCard(onClick = { editProfile = !editProfile }) {
                if (data.profile.isEmpty()) Text("Business profile nahi hai - tap karke banao (ya voice se business_profile).")
                else BoardText(data.profile.render())
                if (editProfile) ProfileEditor(data.profile) { p -> BusinessProfileStore(ctx).save(p); editProfile = false; refresh() }
            }
            ItemCard { BoardText(data.kpi) }
            SectionTitle("Boards")
        }
        if (boards.isEmpty()) item { EmptyNote("Abhi koi plan/research nahi. Bolo 'Diwali campaign plan banao' ya 'competitor research karo'.") }
        items(boards, key = { it.id }) { b ->
            ItemCard(onClick = { expanded = if (expanded == b.id) null else b.id }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(b.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(b.status, style = MaterialTheme.typography.labelMedium)
                }
                Text("${b.kind} · ${DateTimeParse.formatDateTime(b.createdAt)}", style = MaterialTheme.typography.bodySmall)
                if (b.status == "running") Text(b.progress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (expanded == b.id && b.text.isNotBlank()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    BoardText(b.text)
                    Row {
                        TextButton(onClick = { SocialShare.copyToClipboard(ctx, b.title, b.text); toast(ctx, "Copy ho gaya") }) { Text("Copy") }
                        TextButton(onClick = { BoardStore.delete(ctx, b.id); refresh() }) { Text("Delete") }
                    }
                }
                if (b.status == "running") TextButton(onClick = { BusinessJobs.cancel(BoardRecord.groupOf(b.kind)); refresh() }) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun ProfileEditor(p: BusinessProfile, onSave: (BusinessProfile) -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var type by remember { mutableStateOf(p.type) }
    var audience by remember { mutableStateOf(p.audience) }
    var location by remember { mutableStateOf(p.location) }
    var tone by remember { mutableStateOf(p.tone) }
    var platforms by remember { mutableStateOf(p.platforms) }
    var claims by remember { mutableStateOf(p.claims) }
    var phone by remember { mutableStateOf(p.phone) }
    var website by remember { mutableStateOf(p.website) }
    var hours by remember { mutableStateOf(p.hours) }
    var offers by remember { mutableStateOf(p.offers) }
    var review by remember { mutableStateOf(p.reviewLink) }
    Column(Modifier.padding(top = 8.dp)) {
        ProfileField("Name", name) { name = it }; ProfileField("What you sell", type) { type = it }; ProfileField("Audience", audience) { audience = it }
        ProfileField("Location", location) { location = it }; ProfileField("Tone", tone) { tone = it }; ProfileField("Platforms", platforms) { platforms = it }
        ProfileField("Claims allowed", claims) { claims = it }; ProfileField("Offers", offers) { offers = it }; ProfileField("Phone", phone) { phone = it }
        ProfileField("Website", website) { website = it }; ProfileField("Hours", hours) { hours = it }; ProfileField("Review link", review) { review = it }
        OutlinedButton(onClick = {
            onSave(p.copy(name = name, type = type, audience = audience, location = location, tone = tone, platforms = platforms,
                claims = claims, phone = phone, website = website, hours = hours, offers = offers, reviewLink = review))
        }, modifier = Modifier.padding(top = 6.dp)) { Text("Save") }
    }
}

@Composable
private fun ProfileField(label: String, v: String, set: (String) -> Unit) {
    OutlinedTextField(value = v, onValueChange = set, label = { Text(label) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
}

// ------------------------------------------------------------------ Finance

@Composable
private fun FinanceTab(data: BoardData, refresh: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var quotes by remember { mutableStateOf<Map<String, Pair<Double, Double>?>>(emptyMap()) }
    var portfolio by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var newSymbol by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }

    fun load() {
        loading = true
        scope.launch {
            val c = FinanceEngine.client(ctx)
            val q = withContext(Dispatchers.IO) {
                data.watchlist.associateWith { s -> c.getQuote(s)?.let { it.price to it.changePercent } }
            }
            quotes = q
            portfolio = withContext(Dispatchers.IO) { FinanceEngine.portfolioText(ctx) }
            loading = false
        }
    }
    LaunchedEffect(data.watchlist, data.holdings.size) { load() }

    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Watchlist")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { load() }) { Text(if (loading) "Loading…" else "Refresh") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = newSymbol, onValueChange = { newSymbol = it }, label = { Text("Add symbol / company") }, modifier = Modifier.weight(1f), singleLine = true)
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = {
                    val raw = newSymbol
                    if (raw.isNotBlank()) scope.launch {
                        val s = withContext(Dispatchers.IO) { FinanceEngine.resolve(ctx, raw) }
                        FinanceEngine.client(ctx).addToWatchlist(s); newSymbol = ""; refresh()
                    }
                }) { Text("Add") }
            }
        }
        items(data.watchlist, key = { "w-$it" }) { s ->
            val q = quotes[s]
            ItemCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (q != null) {
                        Text(FinanceEngine.fmt(q.first) + "  ")
                        Text(FinanceEngine.pct(q.second), color = if (q.second >= 0) Color(0xFF7BD88F) else Color(0xFFFF7B7B))
                    } else Text(if (loading) "…" else "n/a")
                    TextButton(onClick = { FinanceEngine.client(ctx).removeFromWatchlist(s); refresh() }) { Text("✕") }
                }
            }
        }
        item {
            SectionTitle("Portfolio")
            ItemCard { if (portfolio.isBlank()) Text("…") else BoardText(portfolio) }
            SectionTitle("Price alerts")
            if (data.alerts.isEmpty()) EmptyNote("Koi price alert set nahi hai.")
            OutlinedButton(onClick = {
                scope.launch {
                    val fired = withContext(Dispatchers.IO) { FinanceEngine.checkAlerts(ctx) }
                    toast(ctx, if (fired.isEmpty()) "Koi alert abhi hit nahi hua" else "${fired.size} alert hit"); refresh()
                }
            }) { Text("Check now") }
        }
        items(data.alerts.sortedByDescending { it.createdAt }, key = { "a-" + it.id }) { a ->
            ItemCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${a.symbol} ${a.direction} ${FinanceEngine.fmt(a.price)}", fontWeight = FontWeight.SemiBold)
                        Text(if (a.isArmed) "armed" else "fired ${DateTimeParse.formatDateTime(a.firedAt)} @ ${FinanceEngine.fmt(a.firedPrice)}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        FinanceStore.saveAlerts(ctx, FinanceStore.alerts(ctx).filterNot { it.id == a.id }); FinanceEngine.syncAlertWorker(ctx); refresh()
                    }) { Text(if (a.isArmed) "Cancel" else "Remove") }
                }
            }
        }
        val reports = data.boards.filter { BoardRecord.groupOf(it.kind) == "market" }
        if (reports.isNotEmpty()) item { SectionTitle("Analysis") }
        items(reports, key = { "r-" + it.id }) { b ->
            ItemCard(onClick = { expanded = if (expanded == b.id) null else b.id }) {
                Text(b.title, fontWeight = FontWeight.SemiBold)
                Text("${b.status} · ${DateTimeParse.formatDateTime(b.createdAt)}", style = MaterialTheme.typography.bodySmall)
                if (b.status == "running") Text(b.progress, style = MaterialTheme.typography.bodySmall)
                if (expanded == b.id && b.text.isNotBlank()) BoardText(b.text)
            }
        }
    }
}

// ------------------------------------------------------------------ Missions

@Composable
private fun MissionsTab(missions: List<Mission>, refresh: () -> Unit) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf<String?>(null) }
    val running = missions.filter { MissionRunner.isRunning(it.id) || it.status == "running" || it.status == "planning" }
    val finished = missions - running.toSet()
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        item { SectionTitle("Running (${running.size})") }
        if (running.isEmpty()) item { EmptyNote("Koi mission nahi chal raha. Bolo 'mission chalao: ...'") }
        items(running, key = { "r-" + it.id }) { m -> MissionCard(m, expanded == m.id, { expanded = if (expanded == m.id) null else m.id }) { MissionRunner.cancel(ctx, m.id); refresh() } }
        item { SectionTitle("Finished / delegated (${finished.size})") }
        items(finished, key = { "f-" + it.id }) { m -> MissionCard(m, expanded == m.id, { expanded = if (expanded == m.id) null else m.id }, null) }
    }
}

@Composable
private fun MissionCard(m: Mission, open: Boolean, toggle: () -> Unit, onCancel: (() -> Unit)?) {
    ItemCard(onClick = toggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.goal, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = if (open) 6 else 2, overflow = TextOverflow.Ellipsis)
            Text(m.status, style = MaterialTheme.typography.labelMedium)
        }
        Text((if (m.kind == "delegated") "→ ${m.role} · " else "${m.steps.size} steps · ") + DateTimeParse.formatDateTime(m.createdAt), style = MaterialTheme.typography.bodySmall)
        if (open) {
            m.steps.forEach { s ->
                Text("${s.index + 1}. ${s.tool} [${s.status}] ${s.result.take(200)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
            if (m.summary.isNotBlank()) Text(m.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
        if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
    }
}
