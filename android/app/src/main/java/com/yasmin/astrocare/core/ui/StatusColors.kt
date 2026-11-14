package com.yasmin.astrocare.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yasmin.astrocare.domain.rule.model.OverallStatusValue
import com.yasmin.astrocare.ui.theme.StatusCritical
import com.yasmin.astrocare.ui.theme.StatusInsufficient
import com.yasmin.astrocare.ui.theme.StatusNormal
import com.yasmin.astrocare.ui.theme.StatusWarning

object StatusColors {
    fun of(status: OverallStatusValue): Color = when (status) {
        OverallStatusValue.normal -> StatusNormal
        OverallStatusValue.warning -> StatusWarning
        OverallStatusValue.critical -> StatusCritical
        OverallStatusValue.insufficient_data -> StatusInsufficient
    }

    fun ofAlertLevel(level: String): Color = when (level) {
        "critical" -> StatusCritical
        "warning" -> StatusWarning
        else -> StatusInsufficient
    }
}

/** The label and color both communicate status; color is never the only cue. */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val foreground = when (color) {
        StatusNormal -> if (lightSurface) Color(0xFF14613C) else Color(0xFF8EE7B5)
        StatusWarning -> if (lightSurface) Color(0xFF785000) else Color(0xFFFFD585)
        StatusCritical -> if (lightSurface) Color(0xFFA41F39) else Color(0xFFFF9AAF)
        StatusInsufficient -> if (lightSurface) Color(0xFF46556B) else Color(0xFFD2DEF1)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val label = text.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = foreground,
        modifier = modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}