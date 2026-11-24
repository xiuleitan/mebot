package com.txl.mebot.ui.trading

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.txl.mebot.R
import com.txl.mebot.data.trading.TradingContract
import com.txl.mebot.ui.common.hapticAction

private const val BinancePackage = "com.binance.dev"

internal fun openBinanceFutures(context: Context, contract: TradingContract): Boolean {
    val appUri = Uri.Builder()
        .scheme("bnc").authority("app.binance.com")
        .appendPath("trade").appendPath("trade")
        .appendQueryParameter("at", "futures")
        .appendQueryParameter("symbol", contract.symbol)
        .build()
    val app = Intent(Intent.ACTION_VIEW, appUri)
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .setPackage(BinancePackage)
    if (context.openLink(app)) return true

    val webUri = Uri.Builder()
        .scheme("https").authority("www.binance.com")
        .appendPath("en").appendPath("futures").appendPath(contract.symbol)
        .build()
    return context.openLink(Intent(Intent.ACTION_VIEW, webUri).addCategory(Intent.CATEGORY_BROWSABLE))
}

private fun Context.openLink(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

@Composable
internal fun TradingBinanceTradeButton(contract: TradingContract, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val failure = stringResource(R.string.trading_binance_open_failed)
    val colors = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            onClick = hapticAction {
                if (!openBinanceFutures(context, contract)) Toast.makeText(context, failure, Toast.LENGTH_SHORT).show()
            },
            modifier = modifier,
            color = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
            shape = RoundedCornerShape(6.dp),
        ) {
            Box(
                Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 27.dp).padding(horizontal = 8.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.trading_binance_trade),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
