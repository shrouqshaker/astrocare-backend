package com.yasmin.astrocare.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Quiet, flat cards. A tone is a subtle outline, never a decorative stripe. */
@Composable
fun CareCard(
    modifier: Modifier = Modifier,
    tone: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, tone?.copy(alpha = 0.4f) ?: MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun CareHeader(
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    photoAsset: String? = null,
    photoCredit: String? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (photoAsset != null) {
            CarePhoto(
                assetName = photoAsset,
                description = "Space research imagery",
                credit = photoCredit ?: "NASA",
            )
        }
    }
}

@Composable
fun CareSectionTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (subtitle != null && subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CareMessage(title: String, message: String, modifier: Modifier = Modifier, tone: Color? = null) {
    CareCard(modifier = modifier, tone = tone) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Small metric identifier; its size can grow naturally with system fonts. */
@Composable
fun CareMetricBadge(metric: String, modifier: Modifier = Modifier) {
    val glyph = when (metric) {
        "heart_rate" -> "HR"
        "blood_pressure" -> "BP"
        "spo2" -> "O₂"
        "temperature" -> "°C"
        "sleep" -> "Zz"
        else -> "+"
    }
    val description = when (metric) {
        "heart_rate" -> "Heart rate"
        "blood_pressure" -> "Blood pressure"
        "spo2" -> "Blood oxygen"
        "temperature" -> "Temperature"
        "sleep" -> "Sleep"
        else -> "Measurement"
    }
    Box(
        modifier = modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
            .padding(8.dp).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun CareBulletStep(index: Int, text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 28.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(index.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Optional bundled photo. The original credit remains visible. */
@Composable
fun CarePhoto(assetName: String, description: String, credit: String = "NASA", modifier: Modifier = Modifier) {
    val image = rememberAssetImage(assetName) ?: return
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Image(
            bitmap = image,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height.toFloat()),
        )
        Text(
            text = "Image: $credit",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}
