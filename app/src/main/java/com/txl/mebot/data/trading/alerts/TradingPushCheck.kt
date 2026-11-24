package com.txl.mebot.data.trading.alerts

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal const val TradingPushCheckType = "trading_push_check"

internal class TradingPushCheck(private val timeoutMillis: Long = 20_000L) {
    private data class Pending(
        val deviceId: String,
        val checkId: String = UUID.randomUUID().toString(),
        val receipt: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val checks = Mutex()
    private val pending = AtomicReference<Pending?>()

    suspend fun verify(deviceId: String, send: suspend (String) -> Unit): Boolean = checks.withLock {
        val check = Pending(deviceId)
        pending.set(check)
        try {
            withTimeoutOrNull(timeoutMillis) {
                send(check.checkId)
                check.receipt.await()
                true
            } ?: false
        } finally {
            pending.set(null)
        }
    }

    fun receive(deviceId: String?, checkId: String?) {
        val check = pending.get() ?: return
        if (check.deviceId == deviceId && check.checkId == checkId) check.receipt.complete(Unit)
    }
}
