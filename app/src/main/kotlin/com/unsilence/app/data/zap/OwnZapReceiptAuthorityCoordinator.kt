package com.unsilence.app.data.zap

import android.util.Log
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.repository.UserRepository
import com.unsilence.app.data.wallet.ZapRepository
import com.unsilence.app.data.wallet.ZapPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ZapAuthority"

/**
 * Resolves exactly one service key: the signed-in user's own LNURL zapper.
 * MES remains a synchronous store; network discovery stays in this lifecycle
 * coordinator and only publishes a small tri-state authority into the store.
 */
@Singleton
class OwnZapReceiptAuthorityCoordinator @Inject constructor(
    private val userRepository: UserRepository,
    private val zapRepository: ZapRepository,
    private val zapPreferencesStore: ZapPreferencesStore,
    private val memoryEventStore: MemoryEventStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start(ownerPubkey: String) {
        job?.cancel()
        memoryEventStore.updateOwnZapServiceAuthority(
            ownerPubkey,
            OwnZapServiceAuthority.Unavailable,
        )
        job = scope.launch {
            var publishedAuthority: OwnZapServiceAuthority = OwnZapServiceAuthority.Unavailable
            fun publish(authority: OwnZapServiceAuthority) {
                if (authority == publishedAuthority) return
                publishedAuthority = authority
                memoryEventStore.updateOwnZapServiceAuthority(ownerPubkey, authority)
            }

            var rememberedKeys = try {
                zapPreferencesStore.trustedZapperPubkeys(ownerPubkey)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "could not load own zapper key history", error)
                emptySet()
            }
            if (rememberedKeys.isNotEmpty()) {
                publish(OwnZapServiceAuthority.Trusted(rememberedKeys))
            }

            userRepository.userFlow(ownerPubkey)
                .map { it?.lud16?.trim()?.takeIf(String::isNotEmpty) }
                .distinctUntilChanged()
                .collectLatest { lud16 ->
                    if (lud16 == null) {
                        publish(rememberedKeys.trustedOr(OwnZapServiceAuthority.Unavailable))
                        return@collectLatest
                    }

                    var retryMs = 30_000L
                    while (currentCoroutineContext().isActive) {
                        val resolved = zapRepository.resolveOwnZapServiceAuthority(lud16)
                        val authority = when (resolved) {
                            is OwnZapServiceAuthority.Trusted -> {
                                if (!rememberedKeys.containsAll(resolved.zapperPubkeys)) {
                                    rememberedKeys = try {
                                        zapPreferencesStore.rememberTrustedZapperPubkeys(
                                            ownerPubkey = ownerPubkey,
                                            freshPubkeys = resolved.zapperPubkeys,
                                        )
                                    } catch (error: Exception) {
                                        currentCoroutineContext().ensureActive()
                                        Log.w(TAG, "could not persist own zapper key history", error)
                                        rememberedKeys + resolved.zapperPubkeys
                                    }
                                }
                                OwnZapServiceAuthority.Trusted(rememberedKeys)
                            }
                            OwnZapServiceAuthority.Unavailable ->
                                rememberedKeys.trustedOr(OwnZapServiceAuthority.Unavailable)
                            OwnZapServiceAuthority.Unsupported ->
                                rememberedKeys.trustedOr(OwnZapServiceAuthority.Unsupported)
                        }
                        publish(authority)
                        if (resolved != OwnZapServiceAuthority.Unavailable) break
                        Log.w(TAG, "own zapper key unavailable; retrying in ${retryMs / 1_000}s")
                        delay(retryMs)
                        retryMs = (retryMs * 4).coerceAtMost(10 * 60_000L)
                    }
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

private fun Set<String>.trustedOr(fallback: OwnZapServiceAuthority): OwnZapServiceAuthority =
    if (isNotEmpty()) OwnZapServiceAuthority.Trusted(this) else fallback
