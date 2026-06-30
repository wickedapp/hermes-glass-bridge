package com.wickedapp.rokidtg.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File

/**
 * Thin Kotlin wrapper around the TDLib [Client].
 *
 * Deviation from brief: org.drinkless.tdlib.TdApi.Object has no `extra` field in v1.8.65.
 * The Client API accepts a [Client.ResultHandler] directly on every send() call,
 * so we pass the handler inline rather than storing a map keyed on extra-id.
 * Source AAR: FaiBah/tdlib-android-prebuilt v1.8.65-a17f87c-Java (app/libs/tdlib.aar).
 * Links only to Android system libs — no custom OpenSSL required.
 */
class TdLibClient(
    dbDir: File,
    filesDir: File,
    apiId: Int,
    apiHash: String,
    deviceModel: String = "Rokid Glasses",
    appVersion: String = "0.1.0",
    systemLangCode: String = "en",
) {
    private val _updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = 128)
    val updates = _updates.asSharedFlow()
    private val scope = CoroutineScope(Dispatchers.Default)

    private val client: Client = Client.create(
        /* updateHandler = */ { obj ->
            if (obj is TdApi.Update) {
                scope.launch { _updates.emit(obj) }
            }
        },
        /* updateExceptionHandler = */ { Timber.tag("TG").e(it, "td update error") },
        /* defaultExceptionHandler = */ { Timber.tag("TG").e(it, "td default error") },
    )

    init {
        dbDir.mkdirs()
        filesDir.mkdirs()

        val params = TdApi.SetTdlibParameters().apply {
            databaseDirectory    = dbDir.absolutePath
            this.filesDirectory  = filesDir.absolutePath
            useMessageDatabase   = true
            useChatInfoDatabase  = true
            useFileDatabase      = true
            useSecretChats       = false
            this.apiId           = apiId
            this.apiHash         = apiHash
            this.deviceModel     = deviceModel
            this.applicationVersion = appVersion
            this.systemLanguageCode = systemLangCode
            useTestDc            = false
            databaseEncryptionKey = ByteArray(0)
        }
        send(params) { /* auth state transitions arrive as updates */ }

        // Hard-cap internal storage at 500 MB
        send(TdApi.SetOption("storage_max_files_size", TdApi.OptionValueInteger(500_000_000L))) {}
    }

    fun send(query: TdApi.Function<*>, handler: (TdApi.Object) -> Unit) {
        client.send(query) { obj -> handler(obj) }
    }

    fun close() {
        // v1.8.65 Client has no close() method — send TdApi.Close() to shut down gracefully
        runCatching { client.send(TdApi.Close()) {} }
    }
}
