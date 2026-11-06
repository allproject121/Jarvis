package com.jarvis.assistant.ui.compose.feature.prompts

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.ui.compose.components.CloudAdaptiveNavigation
import com.jarvis.assistant.ui.compose.components.CloudAtmosphericBackground
import com.jarvis.assistant.ui.compose.components.CloudChip
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.components.StaggeredEntrance
import com.jarvis.assistant.ui.compose.components.bounceClick
import com.jarvis.assistant.ui.compose.navigation.ComposeRoutes
import com.jarvis.assistant.ui.compose.theme.ledger

/**
 * Prompt Studio — guided 4-step wizard (Template → Form → Preview → Send) plus
 * the curated prompt library, ported from AndroLLM's PromptLibraryScreen.
 *
 * "Send to model" stores the rendered prompt in [PendingPrompt.text] and
 * navigates to [ComposeRoutes.CHAT].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptsRoute(navigate: (String) -> Unit, back: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: PromptsViewModel = viewModel { PromptsViewModel(app) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val studioState by viewModel.studioUiState.collectAsStateWithLifecycle()
    var showHistory by remember { mutableStateOf(false) }

    val sendToChat: (String) -> Unit = { prompt ->
        PendingPrompt.text = prompt
        navigate(ComposeRoutes.CHAT)
    }

    BackHandler(enabled = studioState.wizardStep != WizardStep.TEMPLATE) {
        viewModel.previousStep()
    }

    CloudAtmosphericBackground {
        CloudAdaptiveNavigation(
            currentRoute = ComposeRoutes.PROMPTS,
            onTabSelected = { tab -> if (tab.route != ComposeRoutes.PROMPTS) navigate(tab.route) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Prompt Studio",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.ledger.deskPaper
                                )
                            )
                            Text(
                                text = when (studioState.wizardStep) {
                                    WizardStep.TEMPLATE -> "${StudioTemplateLibrary.templates.size} templates • ${uiState.favoriteCount} favorites"
                                    WizardStep.FORM -> studioState.selectedTemplate?.title ?: "Details"
                                    WizardStep.PREVIEW -> "Preview • ${studioState.previewMode.label}"
                                    WizardStep.SEND -> "Ready to send"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.ledger.lampDeep,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (studioState.wizardStep != WizardStep.TEMPLATE) viewModel.previousStep() else back()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.ledger.deskPaper)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showHistory = true }) {
                            Icon(Icons.Filled.History, contentDescription = "History", tint = MaterialTheme.ledger.deskInk)
                        }
                        IconButton(onClick = { viewModel.setWizardStep(WizardStep.TEMPLATE) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Reset", tint = MaterialTheme.ledger.deskInk)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                StudioStepper(
                    currentStep = studioState.wizardStep,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )

                if (studioState.wizardStep == WizardStep.PREVIEW) {
                    PreviewModeTabs(
                        selected = studioState.previewMode,
                        onSelect = { viewModel.setPreviewMode(it) },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }

                AnimatedContent(
                    targetState = studioState.wizardStep,
                    transitionSpec = {
                        (slideInHorizontally(animationSpec = tween(300), initialOffsetX = { it / 4 }) + fadeIn(tween(300)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(300), targetOffsetX = { -it / 4 }) + fadeOut(tween(300)))
                    },
                    label = "wizardStep"
                ) { step ->
                    when (step) {
                        WizardStep.TEMPLATE -> TemplateGalleryStep(
                            viewModel = viewModel,
                            studioState = studioState,
                            libraryState = uiState,
                            onTemplateSelected = { viewModel.selectStudioTemplate(it) },
                            onUseLibraryPrompt = sendToChat
                        )
                        WizardStep.FORM -> TemplateFormStep(
                            viewModel = viewModel,
                            studioState = studioState,
                            onNext = { viewModel.nextStep() }
                        )
                        WizardStep.PREVIEW, WizardStep.SEND -> PreviewStep(
                            viewModel = viewModel,
                            studioState = studioState,
                            onSend = { sendToChat(viewModel.sendPrompt()) }
                        )
                    }
                }
            }
        }
    }

    if (showHistory) {
        HistoryDialog(
            history = studioState.history,
            onReuse = { session ->
                viewModel.duplicateSession(session)
                showHistory = false
            },
            onClear = { viewModel.clearHistory() },
            onDismiss = { showHistory = false }
        )
    }
}

@Composable
private fun StudioStepper(currentStep: WizardStep, modifier: Modifier = Modifier) {
    val steps = WizardStep.entries
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        steps.forEachIndexed { index, step ->
            val isCompleted = step.ordinal < currentStep.ordinal
            val isCurrent = step == currentStep
            val bg = when {
                isCompleted -> MaterialTheme.ledger.lampDeep
                isCurrent -> MaterialTheme.ledger.lampAmber
                else -> MaterialTheme.ledger.deskHairline
            }
            val fg = if (isCompleted || isCurrent) MaterialTheme.ledger.inkOnLamp else MaterialTheme.ledger.deskInk
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(bg),
                contentAlignment = Alignment.Center
            ) {
                if (isCompleted) {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.ledger.inkOnLamp, modifier = Modifier.size(16.dp))
                } else {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = fg)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = step.label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        color = if (isCurrent) MaterialTheme.ledger.deskPaper else MaterialTheme.ledger.deskInk
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (index < steps.lastIndex) {
                Box(
                    modifier = Modifier
                        .weight(0.5f)
                        .height(2.dp)
                        .background(if (isCompleted) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.deskHairline)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewModeTabs(selected: PreviewMode, onSelect: (PreviewMode) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PreviewMode.entries.forEach { mode ->
            FilterChip(selected = selected == mode, onClick = { onSelect(mode) }, label = { Text(mode.label) })
        }
    }
}

// ── Step 1: template gallery (+ library) ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateGalleryStep(
    viewModel: PromptsViewModel,
    studioState: StudioUiState,
    libraryState: PromptLibraryUiState,
    onTemplateSelected: (StudioTemplate) -> Unit,
    onUseLibraryPrompt: (String) -> Unit
) {
    val context = LocalContext.current
    var showLibrary by rememberSaveable { mutableStateOf(false) }
    val studioCategoryFilter = studioState.studioCategory
    val grouped = remember { StudioTemplateLibrary.grouped() }
    val categories = StudioCategory.entries

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Studio / Library switch
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showLibrary, onClick = { showLibrary = false }, label = { Text("Studio templates") })
                FilterChip(
                    selected = showLibrary,
                    onClick = { showLibrary = true },
                    label = { Text("Prompt library • ${PromptLibraryData.prompts.size}") }
                )
            }
        }

        if (showLibrary) {
            libraryItems(
                state = libraryState,
                viewModel = viewModel,
                context = context,
                onUse = onUseLibraryPrompt
            )
            return@LazyColumn
        }

        // Category chips
        item {
            StaggeredEntrance(0) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = studioCategoryFilter == null,
                            onClick = { viewModel.selectStudioCategory(null) },
                            label = { Text("All") }
                        )
                    }
                    items(categories) { cat ->
                        FilterChip(
                            selected = studioCategoryFilter == cat,
                            onClick = { viewModel.selectStudioCategory(if (studioCategoryFilter == cat) null else cat) },
                            label = { Text(cat.label) }
                        )
                    }
                }
            }
        }

        if (studioState.history.isNotEmpty()) {
            item {
                StaggeredEntrance(1) {
                    Text(
                        text = "Recent sessions • ${studioState.history.size}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper)
                    )
                }
            }
            item {
                StaggeredEntrance(2) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(studioState.history.take(5)) { session ->
                            AssistChip(
                                onClick = { viewModel.duplicateSession(session) },
                                label = { Text(session.templateTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Filled.History, null, modifier = Modifier.size(14.dp)) }
                            )
                        }
                    }
                }
            }
        }

        val filteredCategories = if (studioCategoryFilter != null) listOf(studioCategoryFilter) else categories
        filteredCategories.forEach { category ->
            val templates = grouped[category] ?: emptyList()
            if (templates.isNotEmpty()) {
                item(key = "header_${category.name}") {
                    StaggeredEntrance(3) {
                        Text(
                            text = category.label,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper)
                        )
                    }
                }
                items(templates, key = { it.id }) { template ->
                    StaggeredEntrance(0, instant = true) {
                        StudioTemplateCard(
                            template = template,
                            isFavorite = template.id in libraryState.favorites,
                            onToggleFavorite = { viewModel.toggleFavorite(template.id) },
                            onUse = { onTemplateSelected(template) },
                            onCopy = { copyToClipboard(context, template.promptTemplate, "Template copied") }
                        )
                    }
                }
            }
        }

        item {
            StaggeredEntrance(4) {
                StudioSettingsCard(settings = studioState.settings, onUpdate = { viewModel.updateSettings(it) })
            }
        }
    }
}

/** AndroLLM's prompt library: search, category filter chips, favorites, one-tap use. */
@OptIn(ExperimentalMaterial3Api::class)
private fun androidx.compose.foundation.lazy.LazyListScope.libraryItems(
    state: PromptLibraryUiState,
    viewModel: PromptsViewModel,
    context: Context,
    onUse: (String) -> Unit
) {
    item {
        OutlinedTextField(
            value = state.query,
            onValueChange = { viewModel.updateQuery(it) },
            placeholder = { Text("Search prompts") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.ledger.lampAmber,
                unfocusedBorderColor = MaterialTheme.ledger.deskHairline
            )
        )
    }
    item {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.categories) { cat ->
                FilterChip(
                    selected = state.selectedCategory == cat,
                    onClick = { viewModel.selectCategory(cat) },
                    label = { Text(cat.label) }
                )
            }
        }
    }
    if (state.prompts.isEmpty()) {
        item {
            Text(
                text = "No prompts match your search.",
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.ledger.deskInk),
                modifier = Modifier.padding(vertical = 24.dp)
            )
        }
    }
    items(state.prompts, key = { "lib_" + it.id }) { prompt ->
        val isFavorite = prompt.id in state.favorites
        CloudGlassCard(modifier = Modifier.fillMaxWidth(), onClick = { onUse(prompt.text) }) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = prompt.title,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = prompt.description,
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { viewModel.toggleFavorite(prompt.id) }) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "Favorite",
                            tint = if (isFavorite) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInk
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    CloudChip(text = prompt.category.label, accentColor = MaterialTheme.ledger.lampDeep)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { copyToClipboard(context, prompt.text, "Prompt copied") },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.ledger.deskInk, modifier = Modifier.size(18.dp))
                        }
                        Button(onClick = { onUse(prompt.text) }, modifier = Modifier.height(36.dp)) {
                            Text("Use", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioTemplateCard(
    template: StudioTemplate,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onUse: () -> Unit,
    onCopy: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth(), onClick = onUse) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.ledger.deskPaper.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = template.icon, contentDescription = null, tint = MaterialTheme.ledger.lampGlow, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = template.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = template.description,
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInk
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = template.exampleUseCase,
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk, fontStyle = FontStyle.Italic),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CloudChip(
                        text = template.category.label,
                        accentColor = when (template.category) {
                            StudioCategory.CODE, StudioCategory.EXPLAIN -> MaterialTheme.ledger.lampDeep
                            StudioCategory.WRITING, StudioCategory.REWRITE -> MaterialTheme.ledger.lampAmber
                            StudioCategory.EMAIL, StudioCategory.SOCIAL_POST -> MaterialTheme.ledger.lampGlow
                            else -> MaterialTheme.ledger.deskInk
                        }
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = when {
                            template.qualityScore >= 90 -> MaterialTheme.ledger.lampDeep.copy(alpha = 0.15f)
                            template.qualityScore >= 80 -> MaterialTheme.ledger.lampAmber.copy(alpha = 0.15f)
                            else -> MaterialTheme.ledger.deskHairline.copy(alpha = 0.3f)
                        }
                    ) {
                        Text(
                            text = "${template.qualityScore} • ${template.usefulnessTag}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.ledger.deskPaper),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.ledger.deskInk, modifier = Modifier.size(18.dp))
                    }
                    Button(onClick = onUse, modifier = Modifier.height(36.dp)) {
                        Text("Use", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

// ── Step 2: dynamic form ─────────────────────────────────────────────────────

@Composable
private fun TemplateFormStep(
    viewModel: PromptsViewModel,
    studioState: StudioUiState,
    onNext: () -> Unit
) {
    val template = studioState.selectedTemplate
    if (template == null) {
        Box(Modifier.fillMaxSize().padding(20.dp)) {
            Text("Pick a template first.", color = MaterialTheme.ledger.deskInk)
        }
        return
    }
    val formValues = studioState.formValues
    val errors = studioState.validationErrors
    val showAdvanced = studioState.settings.showAdvancedFields
    val context = LocalContext.current
    val visibleFields = template.fields.filter { !it.isAdvanced || showAdvanced }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }?.take(8000) ?: ""
                val codeField = template.fields.find { f -> f.type == PromptFieldType.CODE }?.id ?: "code"
                viewModel.updateField(codeField, text)
                Toast.makeText(context, "File loaded", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StaggeredEntrance(0) {
                Column {
                    Text(
                        text = template.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper)
                    )
                    Text(
                        text = template.description,
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                    )
                }
            }
        }
        items(visibleFields, key = { it.id }) { field ->
            StaggeredEntrance(0, instant = true) {
                FieldRenderer(
                    field = field,
                    value = formValues[field.id] ?: "",
                    error = errors[field.id],
                    onValueChange = { viewModel.updateField(field.id, it) },
                    onFilePick = { filePicker.launch(arrayOf("text/*", "application/*")) }
                )
            }
        }
        item {
            StaggeredEntrance(1) {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { viewModel.resetForm() }, modifier = Modifier.weight(1f)) {
                            Text("Reset")
                        }
                        Button(onClick = onNext, modifier = Modifier.weight(1f)) {
                            Text("Preview")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (errors.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Please fix:",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                )
                                errors.values.forEach { msg ->
                                    Text(
                                        text = "• $msg",
                                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onErrorContainer)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldRenderer(
    field: PromptField,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    onFilePick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.ledger.lampAmber,
        unfocusedBorderColor = MaterialTheme.ledger.deskHairline
    )
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = field.label + if (field.required) " *" else "",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.ledger.deskPaper),
                modifier = Modifier.weight(1f)
            )
            if (field.isAdvanced) {
                CloudChip(text = "Advanced", accentColor = MaterialTheme.ledger.deskInk)
            }
        }
        if (field.helperText.isNotBlank()) {
            Text(text = field.helperText, style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk))
        }
        when (field.type) {
            PromptFieldType.SELECT -> {
                // Plain DropdownMenu anchored to a read-only field (avoids ExposedDropdownMenuBox API churn).
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(field.placeholder) },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { expanded = true }
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        field.options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    onValueChange(option)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
            PromptFieldType.CODE -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text(field.placeholder) },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 6,
                    colors = fieldColors
                )
                if (field.id == "code") {
                    TextButton(onClick = onFilePick) {
                        Text("Upload file", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            PromptFieldType.FILE -> {
                OutlinedButton(onClick = onFilePick, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Description, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Choose file")
                }
                if (value.isNotBlank()) {
                    Text(text = "Selected: ${value.take(40)}", style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk))
                }
            }
            else -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text(field.placeholder) },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = if (field.type == PromptFieldType.TEXT_AREA) 3 else 1,
                    colors = fieldColors
                )
            }
        }
        if (error != null) {
            Text(text = error, style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.error))
        }
    }
}

// ── Step 3/4: preview + send ─────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PreviewStep(
    viewModel: PromptsViewModel,
    studioState: StudioUiState,
    onSend: () -> Unit
) {
    val context = LocalContext.current
    val previewText = studioState.previewText
    val template = studioState.selectedTemplate

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (template != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskWalnut),
                border = BorderStroke(1.dp, MaterialTheme.ledger.deskHairline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = "Template: ${template.title}", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper))
                    Text(text = "Category: ${template.category.label}", style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk))
                    Text(text = "Quality: ${template.qualityScore} • ${template.usefulnessTag}", style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk))
                }
            }
        }

        if (studioState.settings.enableRefinementSuggestions) {
            Text(
                text = "Refine prompt",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityHelper.entries.forEach { helper ->
                    AssistChip(
                        onClick = { viewModel.applyQualityHelper(helper) },
                        label = { Text(helper.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }

        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.ledger.lampAmber,
            unfocusedBorderColor = MaterialTheme.ledger.deskHairline
        )
        when (studioState.previewMode) {
            PreviewMode.FORM -> {
                val filled = studioState.formValues.filterValues { it.isNotBlank() }
                if (filled.isEmpty()) {
                    Text(
                        text = "No form values (prompt was reused or edited directly).",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                    )
                }
                filled.forEach { (k, v) ->
                    Text(
                        text = "$k: ${v.take(120)}",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskPaper),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            PreviewMode.PREVIEW -> {
                CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text(
                            text = "Final prompt preview",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        OutlinedTextField(
                            value = previewText,
                            onValueChange = { viewModel.updateRawPrompt(it) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 8,
                            colors = fieldColors
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            OutlinedButton(onClick = { viewModel.resetForm() }) { Text("Reset") }
                            OutlinedButton(onClick = { copyToClipboard(context, previewText, "Copied") }) { Text("Copy") }
                            Button(onClick = {
                                viewModel.updateRawPrompt(previewText)
                                viewModel.setPreviewMode(PreviewMode.PREVIEW)
                            }) {
                                Text("Regenerate")
                            }
                        }
                    }
                }
            }
            PreviewMode.RAW -> {
                CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text(
                            text = "Raw prompt (plain text)",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper)
                        )
                        OutlinedTextField(
                            value = studioState.rawPrompt,
                            onValueChange = { viewModel.updateRawPrompt(it) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            minLines = 10,
                            colors = fieldColors
                        )
                    }
                }
            }
        }

        val hasErrors = studioState.validationErrors.isNotEmpty()
        if (hasErrors) {
            Text(
                text = "Fix validation errors before sending",
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.error)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { viewModel.resetWizard() }, modifier = Modifier.weight(1f)) {
                Text("Start over")
            }
            Button(
                onClick = {
                    if (viewModel.validateForm()) {
                        onSend()
                    } else {
                        Toast.makeText(context, "Please fill required fields", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = previewText.isNotBlank()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Send to model")
            }
        }

        if (studioState.history.isNotEmpty()) {
            Text(
                text = "Recent sessions",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper)
            )
            studioState.history.take(5).forEach { session ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .bounceClick { viewModel.duplicateSession(session) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskWalnut),
                    border = BorderStroke(1.dp, MaterialTheme.ledger.deskHairline)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = session.templateTitle,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.ledger.deskPaper)
                        )
                        Text(
                            text = session.finalPrompt.take(120) + "...",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { viewModel.duplicateSession(session) }) {
                                Text("Reuse", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { copyToClipboard(context, session.finalPrompt, "Copied") }) {
                                Text("Copy", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            TextButton(onClick = { viewModel.clearHistory() }) {
                Text("Clear history", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ── Settings card ────────────────────────────────────────────────────────────

@Composable
private fun StudioSettingsCard(
    settings: PromptStudioSettings,
    onUpdate: (PromptStudioSettings) -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Prompt Studio settings",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper)
            )
            SettingSwitch("Auto-preview", settings.autoPreviewOn) { onUpdate(settings.copy(autoPreviewOn = it)) }
            SettingSwitch("Show advanced fields", settings.showAdvancedFields) { onUpdate(settings.copy(showAdvancedFields = it)) }
            SettingSwitch("Save prompt history", settings.savePromptHistory) { onUpdate(settings.copy(savePromptHistory = it)) }
            SettingSwitch("Refinement suggestions", settings.enableRefinementSuggestions) {
                onUpdate(settings.copy(enableRefinementSuggestions = it))
            }

            var expanded by remember { mutableStateOf(false) }
            val defaultTitle = settings.defaultTemplateId?.let { StudioTemplateLibrary.byId(it)?.title } ?: "None"
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = defaultTitle,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Default template") },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { expanded = true }
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.heightIn(max = 320.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text("None") },
                        onClick = {
                            onUpdate(settings.copy(defaultTemplateId = null))
                            expanded = false
                        }
                    )
                    StudioTemplateLibrary.templates.forEach { t ->
                        DropdownMenuItem(
                            text = { Text(t.title) },
                            onClick = {
                                onUpdate(settings.copy(defaultTemplateId = t.id))
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.ledger.deskPaper)
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.ledger.inkOnLamp,
                checkedTrackColor = MaterialTheme.ledger.lampAmber,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = MaterialTheme.ledger.deskInk,
                uncheckedTrackColor = MaterialTheme.ledger.deskWalnutDeep,
                uncheckedBorderColor = MaterialTheme.ledger.deskHairline
            )
        )
    }
}

// ── History dialog (AndroLLM's history action was a no-op placeholder) ────────

@Composable
private fun HistoryDialog(
    history: List<PromptSession>,
    onReuse: (PromptSession) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Prompt history") },
        text = {
            if (history.isEmpty()) {
                Text("No prompts sent yet.")
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    history.forEach { session ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onReuse(session) }
                                .padding(8.dp)
                        ) {
                            Text(session.templateTitle, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = session.finalPrompt,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (history.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
    )
}

private fun copyToClipboard(context: Context, text: String, toast: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Prompt", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}
