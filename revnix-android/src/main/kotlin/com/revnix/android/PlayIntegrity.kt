package com.revnix.android

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Classic Play Integrity token source for [com.revnix.RevnixConfig.deviceIntegrity].
 */
public fun playIntegrity(context: Context): suspend (String) -> String? {
    val manager = IntegrityManagerFactory.create(context.applicationContext)
    return { nonce ->
        suspendCancellableCoroutine { continuation ->
            val task = manager.requestIntegrityToken(
                IntegrityTokenRequest.builder().setNonce(nonce).build(),
            )
            task.addOnSuccessListener { continuation.resume(it.token()) }
            task.addOnFailureListener { continuation.resumeWithException(it) }
        }
    }
}
