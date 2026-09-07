package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
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
 * Renders nothing when balance is disabled for the instance or the value
 * can't be fetched. The 2-minute cache lives in ProviderBalance, and the
 * produceState key covers enabled/apiPath/resultPath so edits re-fetch.
 */
@Composable
fun ProviderBalanceText(
    instance: ProviderInstance,
    modifier: Modifier = Modifier,
) {
    if (!instance.balanceEnabled) return
    val context = LocalContext.current
    val value by produceState(
        initialValue = null as String?,
        key1 = instance.id,
        key2 = instance.balanceEnabled,
        key3 = instance.balanceApiPath,
        key4 = instance.balanceResultPath,
    ) {
        value = ProviderBalance.fetchBalance(context, instance)
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
