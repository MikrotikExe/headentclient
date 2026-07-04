package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Jednotne stavove obrazovky (nacitavanie / ziadny server / chyba / prazdne)
 * pouzivane naprie Kanalmi, Radiom, Archivom a EPG.
 */
@Composable
fun StatusMessage(
    icon: ImageVector,
    title: String,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (isModernUi()) {
        // Moderny rezim (M331): ikona v teal cipe, tucny titulok, akcia ako pilulka
        val cs = MaterialTheme.colorScheme
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(18.dp))
                        .background(cs.primaryContainer.copy(alpha = if (isLightTheme()) 0.55f else 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = cs.primary,
                        modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text(title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(detail, style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                if (actionLabel != null && onAction != null) {
                    Spacer(Modifier.height(18.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(cs.primary)
                            .dpadFocusable(RoundedCornerShape(999.dp))
                            .clickable { onAction() }
                            .padding(horizontal = 26.dp, vertical = 11.dp)
                    ) {
                        Text(actionLabel, color = cs.onPrimary,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        return
    }
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
fun LoadingStatus(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun NoServerStatus(modifier: Modifier = Modifier) {
    StatusMessage(
        icon = Icons.Default.Dns,
        title = stringResource(R.string.no_active_server),
        detail = stringResource(R.string.no_server_hint),
        modifier = modifier
    )
}

@Composable
fun ErrorStatus(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    StatusMessage(
        icon = Icons.Default.CloudOff,
        title = stringResource(R.string.error_connection_title),
        detail = message,
        actionLabel = stringResource(R.string.retry),
        onAction = onRetry,
        modifier = modifier
    )
}

@Composable
fun EmptyStatus(message: String, modifier: Modifier = Modifier) {
    StatusMessage(
        icon = Icons.Default.Inbox,
        title = message,
        modifier = modifier
    )
}
