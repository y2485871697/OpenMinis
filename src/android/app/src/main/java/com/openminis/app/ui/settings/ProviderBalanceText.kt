package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.provider.balance.ProviderBalance
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Wallet icon + balance text, mirroring RikkaHub's ProviderBalanceText.
 * Renders nothing when balance is disabled for the instance or the value
 * can't be fetched.
 *
 * ANR-safety: the initial fetch is deferred by 300 ms so a screen full of
 * balance widgets (provider list + model picker + top bar) does not all
 * fire HTTP + Keystore reads at the exact same frame. The fetch runs on
 * IO via ProviderBalance.fetchBalance; only the result is posted back.
 * A global refreshTrigger StateFlow (bumped on stream end / config edits)
 * drives re-fetches; a 1-second dedup window collapses concurrent readouts
 * of the same provider into one request.
 */
@Composable
fun ProviderBalanceText(
    instance: ProviderInstance,
    modifier: Modifier = Modifier,
) {
    if (!instance.balanceEnabled) return
    val context = LocalContext.current
    val tick by ProviderBalance.refreshTrigger.collectAsState()
    val value by produceState(
        initialValue = "~",
        key1 = instance.id,
        key2 = instance.balanceApiPath,
        key3 = instance.balanceResultPath,
        key4 = tick,
    ) {
        // [T-android-provider-balance-anr] Defer the first fetch so mount
        // storms don't all hit Keystore + network in the same frame.
        delay(300)
        value = ProviderBalance.fetchBalance(context, instance) ?: "~"
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
