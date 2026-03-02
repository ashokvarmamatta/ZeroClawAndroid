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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─── InfoScreen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(onBack: () -> Unit) {
    var selectedTabIndex by remember { mutableStateOf(0) }
    val sections = ALL_GUIDE_SECTIONS

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Setup Guide", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Everything you need to get started", fontSize = 11.sp,
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
                sections.forEachIndexed { index, section ->
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
                            Text(section.emoji, fontSize = 16.sp)
                            Text(
                                section.label,
                                fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
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
                GuideSectionContent(section = sections[tabIndex])
            }
        }
    }
}

// ─── Section content ──────────────────────────────────────────────────────────

@Composable
fun GuideSectionContent(section: GuideSection) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { IntroCard(section) }
        itemsIndexed(section.steps) { _, step ->
            StepCard(step, section.accentColor)
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
fun StepCard(step: GuideStep, accentColor: Color) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // Header row: badge + emoji + title + chevron
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
