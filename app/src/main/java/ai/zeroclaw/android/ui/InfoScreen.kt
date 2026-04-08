package ai.zeroclaw.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.SharedPreferences

// ─── InfoScreen ───────────────────────────────────────────────────────────────

/**
 * Helper to track which "NEW" guide steps the user has seen.
 * Uses SharedPreferences for simplicity — stores step keys like "tools_11".
 */
object SeenStepsTracker {
    private const val PREFS_NAME = "zeroclaw_seen_steps"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasSeen(context: Context, sectionId: String, stepNumber: Int): Boolean =
        prefs(context).getBoolean("${sectionId}_$stepNumber", false)

    fun markSeen(context: Context, sectionId: String, stepNumber: Int) {
        prefs(context).edit().putBoolean("${sectionId}_$stepNumber", true).apply()
    }

    fun hasNewInSection(context: Context, section: GuideSection): Boolean =
        section.steps.any { it.isNew && !hasSeen(context, section.id, it.number) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(onBack: () -> Unit, startSectionId: String? = null) {
    val context = LocalContext.current
    // Tab 0 = About, tabs 1+ = guide sections
    var selectedTabIndex by remember { mutableStateOf(0) }
    val guideSections = ALL_GUIDE_SECTIONS

    // If a specific section is requested (e.g. from Settings redirect button), jump to it
    LaunchedEffect(startSectionId) {
        if (startSectionId != null) {
            val idx = guideSections.indexOfFirst { it.id == startSectionId }
            if (idx >= 0) selectedTabIndex = idx + 1  // +1 because tab 0 is "About"
        }
    }

    // Track seen state so UI recomposes when steps are marked seen
    var seenVersion by remember { mutableIntStateOf(0) }

    data class TabDef(val emoji: String, val label: String, val hasNew: Boolean)
    @Suppress("UNUSED_EXPRESSION")
    seenVersion // trigger recomposition when a step is marked seen
    val tabs = listOf(TabDef("🦀", "About", false)) +
        guideSections.map { section ->
            TabDef(section.emoji, section.label, SeenStepsTracker.hasNewInSection(context, section))
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ZeroClaw", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Info & Setup Guide", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // Tab row
            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = 12.dp
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(tab.emoji, fontSize = 16.sp)
                            Text(
                                tab.label,
                                fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                            // NEW dot on tabs that have unseen new features
                            if (tab.hasNew) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFF1744))
                                )
                            }
                        }
                    }
                }
            }

            // Tab content with animated transitions
            AnimatedContent(
                targetState = selectedTabIndex,
                transitionSpec = {
                    fadeIn() + slideInHorizontally() togetherWith fadeOut() + slideOutHorizontally()
                },
                label = "tab_content"
            ) { tabIndex ->
                if (tabIndex == 0) {
                    AboutTabContent()
                } else {
                    GuideSectionContent(
                        section = guideSections[tabIndex - 1],
                        onStepSeen = { stepNumber ->
                            SeenStepsTracker.markSeen(context, guideSections[tabIndex - 1].id, stepNumber)
                            seenVersion++
                        }
                    )
                }
            }
        }
    }
}

// ─── About tab ───────────────────────────────────────────────────────────────

@Composable
fun AboutTabContent() {
    val brandRed = Color(0xFFE53935)
    val brandOrange = Color(0xFFFF6D00)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero header
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    brandRed.copy(alpha = 0.15f),
                                    brandOrange.copy(alpha = 0.10f)
                                )
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )
                        .padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🦀", fontSize = 52.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "ZeroClaw",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 28.sp,
                            color = brandRed
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "v$APP_VERSION_NAME",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Turn your Android phone into a 24/7 AI agent daemon.\nNo PC. No cloud. Just your phone.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            lineHeight = 21.sp
                        )
                    }
                }
            }
        }

        // App details
        item {
            SectionHeader("App Details")
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(4.dp)) {
                    APP_INFO_ITEMS.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(item.icon, fontSize = 20.sp)
                            Spacer(Modifier.width(14.dp))
                            Text(
                                item.label,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(100.dp)
                            )
                            Text(
                                item.value,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (index < APP_INFO_ITEMS.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }

        // Features
        item {
            SectionHeader("Features")
        }
        items(APP_FEATURES) { feature ->
            FeatureCard(feature)
        }

        // Supported providers
        item {
            SectionHeader("Supported LLM Providers")
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(4.dp)) {
                    SUPPORTED_PROVIDERS.forEachIndexed { index, provider ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(provider.emoji, fontSize = 22.sp)
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    provider.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    provider.models,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (index < SUPPORTED_PROVIDERS.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }

        // Architecture
        item {
            SectionHeader("Architecture")
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ArchRow("Language", "Kotlin")
                    ArchRow("UI", "Jetpack Compose + Material 3")
                    ArchRow("Navigation", "Jetpack Navigation Compose")
                    ArchRow("Background", "Foreground Service + Boot Receiver")
                    ArchRow("Networking", "OkHttp + Retrofit")
                    ArchRow("Storage", "Room DB + DataStore Preferences")
                    ArchRow("Offline AI", "LiteRT LM (Gemma 4)")
                    ArchRow("Tunneling", "Cloudflare Tunnel / ngrok")
                }
            }
        }

        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

@Composable
private fun FeatureCard(feature: FeatureItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(feature.icon, fontSize = 26.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    feature.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    feature.description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun ArchRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Text(
            value,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ─── Section content ──────────────────────────────────────────────────────────

@Composable
fun GuideSectionContent(section: GuideSection, onStepSeen: (Int) -> Unit = {}) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { IntroCard(section) }
        itemsIndexed(section.steps) { _, step ->
            val showNew = step.isNew && !SeenStepsTracker.hasSeen(context, section.id, step.number)
            StepCard(step, section.accentColor, showNew) {
                onStepSeen(step.number)
            }
        }
        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

// ─── Intro card ───────────────────────────────────────────────────────────────

@Composable
fun IntroCard(section: GuideSection) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = section.accentColor.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(section.emoji, fontSize = 34.sp)
            Column {
                Text(section.label, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    color = section.accentColor)
                Spacer(Modifier.height(4.dp))
                Text(section.intro, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface, lineHeight = 20.sp)
            }
        }
    }
}

// ─── Step card ────────────────────────────────────────────────────────────────

@Composable
fun StepCard(step: GuideStep, accentColor: Color, showNewBadge: Boolean = false, onSeen: () -> Unit = {}) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                expanded = !expanded
                if (showNewBadge) onSeen()
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (showNewBadge)
                Color(0xFFFF1744).copy(alpha = 0.06f)
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // Header row: badge + emoji + title + NEW tag + chevron
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Numbered circle badge
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(accentColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(step.number.toString(), color = Color.White,
                        fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Text(step.icon, fontSize = 22.sp)

                Text(
                    step.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis
                )

                // NEW badge
                if (showNewBadge) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFFF1744))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("NEW", color = Color.White, fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
                    }
                }

                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Short description (always visible)
            Spacer(Modifier.height(10.dp))
            Text(step.description, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp)

            // Expanded detail + code snippet
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    if (step.detail.isNotBlank()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Text(step.detail, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface, lineHeight = 18.sp)
                    }
                    if (step.codeSnippet.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        CopyableSnippet(step.codeSnippet, accentColor, context)
                    }
                }
            }

            // "Tap for details" hint when collapsed and detail exists
            if (!expanded && step.detail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("Tap for full details →", fontSize = 11.sp,
                    color = accentColor, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ─── Copyable code snippet ────────────────────────────────────────────────────

@Composable
fun CopyableSnippet(text: String, accentColor: Color, context: Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .clickable {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("ZeroClaw", text))
                Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = accentColor,
            modifier = Modifier.weight(1f),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.ContentCopy, "Copy", tint = accentColor,
            modifier = Modifier.size(16.dp))
    }
}