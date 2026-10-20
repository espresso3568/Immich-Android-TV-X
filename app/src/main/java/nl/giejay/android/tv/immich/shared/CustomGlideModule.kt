package nl.giejay.android.tv.immich.shared

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import nl.giejay.android.tv.immich.api.ApiClientFactory
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.DEBUG_MODE
import nl.giejay.android.tv.immich.shared.prefs.DISABLE_SSL_VERIFICATION
import nl.giejay.android.tv.immich.shared.prefs.LiveSharedPreferences
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import java.io.InputStream
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull


@GlideModule
class CustomGlideModule : AppGlideModule() {
    private var factory: OkHttpUrlLoader.Factory = OkHttpUrlLoader.Factory()
    private var registry: Registry? = null
    private val prefs: LiveSharedPreferences =
        LiveSharedPreferences(PreferenceManager.sharedPreference)

    init {
        prefs.listenMultiple(listOf(API_KEY.key(), DEBUG_MODE.key(), DISABLE_SSL_VERIFICATION.key()))
            .observeForever {
                reloadFactory(
                    PreferenceManager.get(API_KEY), // listenMultiple's raw value is still encrypted
                    it[DISABLE_SSL_VERIFICATION.key()] == true
                )
            }
    }

    private fun reloadFactory(apiKey: String, disableSsl: Boolean) {
        factory = if (PreferenceManager.isLoggedId()) {
            OkHttpUrlLoader.Factory(
                ApiClientFactory.getClient(
                    disableSsl,
                    apiKey,
                    // never enable debug mode of responses in glide, too much data
                    false,
                    apiKeyProvider = { request ->
                        // Glide can retain a loader after registry replacement or re-login.
                        // Resolve the current key when sending, and reject old-server requests.
                        val server = PreferenceManager.hostName.toHttpUrlOrNull()
                        if (server == null || server.host != request.url.host ||
                            server.port != request.url.port || server.scheme != request.url.scheme) {
                            throw IOException("Image request does not match the active Immich server.")
                        }
                        PreferenceManager.get(API_KEY)
                    }
                )
            )
        } else {
            OkHttpUrlLoader.Factory()
        }
        this.registry?.replace(
            GlideUrl::class.java,
            InputStream::class.java, factory
        )
    }

    override fun applyOptions(context: Context, builder: GlideBuilder) {
    }

    override fun registerComponents(
        context: Context, glide: Glide, registry: Registry
    ) {
        this.registry = registry
        // override default loader with one that attaches headers
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java, factory
        )
    }
}
