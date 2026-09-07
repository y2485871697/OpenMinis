package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.provider.balance.ProviderBalance

/**
 * Wallet icon + balance text, mirroring RikkaHub's ProviderBalanceText.
 * Renders nothing when balance is disabled for the instance or no value
 * has ever been fetched.
 *
 * Real-time behavior: the first composition paints the last-known value
 * instantly (no flash of nothing), then a produceState keyed on the config
 * fields AND [ProviderBalance.refreshTrigger] re-fetches on every bump —
 * e.g. when a streaming turn ends (consumption just happened) or the user
 * edits the balance settings. Concurrent readouts of the same provider
 * share one request via ProviderBalance's dedup window.
 */
@Composable
fun ProviderBalanceText(
    instance: ProviderInstance,
    modifier: Modifier = Modifier,
) {
    if (!instance.balanceEnabled) return
    val context = LocalContext.current
    // Trigger tick — bumps when any consumption event says "re-fetch now".
    val tick by ProviderBalance.refreshTrigger.collectAsState()
    // Instant first paint from the last-known-good map.
    var value by remember(instance.id) {
        mutableStateOf(ProviderBalance.lastKnownBalance(instance))
    }
    LaunchedEffect(instance.id, instance.balanceApiPath, instance.balanceResultPath) {
        value = ProviderBalance.lastKnownBalance(instance)
    }
    // Live fetch — runs on first composition and on every trigger bump.
    LaunchedEffect(instance.id, instance.balanceEnabled, instance.balanceApiPath, instance.balanceResultPath, tick) {
        val fresh = ProviderBalance.fetchBalance(context, instance)
        if (fresh != null) value = fresh
    }
    if (value == null) return

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_wallet_balance),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = value ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
