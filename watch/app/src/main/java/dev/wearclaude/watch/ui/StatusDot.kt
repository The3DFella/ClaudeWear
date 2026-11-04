package dev.wearclaude.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme

@Composable
fun statusColor(status: String): Color = when (statusColorRole(status)) {
    StatusColorRole.Primary -> MaterialTheme.colorScheme.primary
    StatusColorRole.Amber -> MaterialTheme.colorScheme.tertiary
    StatusColorRole.Green -> Color(0xFF4CAF50)
    StatusColorRole.Error -> MaterialTheme.colorScheme.error
    StatusColorRole.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
    StatusColorRole.Outline -> MaterialTheme.colorScheme.outline
}

@Composable
fun StatusDot(status: String, size: Dp = 10.dp) {
    Box(Modifier.size(size).background(statusColor(status), CircleShape))
}
