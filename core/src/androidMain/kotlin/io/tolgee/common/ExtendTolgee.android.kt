package io.tolgee.common

import android.content.Context
import android.content.res.Resources
import androidx.annotation.ArrayRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import io.tolgee.Tolgee
import io.tolgee.TolgeeAndroid
import io.tolgee.model.TolgeeMessageParams
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.cache.*
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * A platform-specific instance of [HttpClient] configured for Android with default settings.
 *
 * This client is initialized with Android-specific configurations, enabling functionalities like
 * automatic handling of HTTP redirects and caching support through the [HttpCache] feature.
 * It serves as the default HTTP client used in platform-dependent operations where HTTP communication is needed.
 */
internal actual val platformHttpClient: HttpClient = HttpClient(Android) {
    followRedirects = true
    install(HttpCache)
}

/**
 * Creates a platform-specific instance of the Tolgee library.
 *
 * @param config The configuration object required to initialize Tolgee.
 * @return A platform-specific implementation of the Tolgee library.
 */
internal actual fun createPlatformTolgee(config: Tolgee.Config): PlatformTolgee {
    return TolgeeAndroid(config)
}

/**
 * Represents a platform-specific CoroutineContext used for network operations.
 *
 * This property provides a CoroutineContext optimized for performing I/O operations,
 * ensuring efficient execution of network-related tasks. It is typically used in
 * scenarios where network requests or other I/O-bound tasks need to run on a
 * suitable dispatcher.
 *
 * By default, this is set to `Dispatchers.IO`, which is designed for offloading blocking
 * I/O tasks to a shared pool of threads.
 */
internal actual val platformNetworkContext: CoroutineContext
    get() = Dispatchers.IO

/**
 * Returns a localized formatted string from [Tolgee] cache or the application's package's
 * default string table, substituting the format arguments as defined in
 * [java.util.Formatter] and [java.lang.String.format].
 *
 * @param tolgee The [Tolgee] instance to get the cached string from.
 * @param resId Resource id for the format string
 * @return The string data associated with the resource, formatted and
 *         stripped of styled text information.
 */
fun Context.getStringInstant(tolgee: Tolgee, @StringRes resId: Int): String {
    return (tolgee as? TolgeeAndroid)?.instant(this, resId)
        ?: TolgeeAndroid.getKeyFromResources(this, resId)?.let {
            tolgee.instant(key = it, parameters = TolgeeMessageParams.None)
        } ?: this.getString(resId)
}

/**
 * Returns a localized formatted string from [Tolgee] cache or the application's package's
 * default string table, substituting the format arguments as defined in
 * [java.util.Formatter] and [java.lang.String.format].
 *
 * @param resId Resource id for the format string
 * @return The string data associated with the resource, formatted and
 *         stripped of styled text information.
 */
fun Context.getStringInstant(@StringRes resId: Int): String {
    val instance = Tolgee.instance ?: return this.getString(resId)
    return this.getStringInstant(instance, resId)
}

/**
 * Returns a localized formatted string from [Tolgee] cache or the application's package's
 * default string table, substituting the format arguments as defined in
 * [java.util.Formatter] and [java.lang.String.format].
 *
 * @param tolgee The [Tolgee] instance to get the cached string from.
 * @param resId Resource id for the format string
 * @param formatArgs The format arguments that will be used for
 *                   substitution.
 * @return The string data associated with the resource, formatted and
 *         stripped of styled text information.
 */
fun Context.getStringInstant(tolgee: Tolgee, @StringRes resId: Int, vararg formatArgs: Any): String {
    return (tolgee as? TolgeeAndroid)?.instant(this, resId, *formatArgs)
        ?: TolgeeAndroid.getKeyFromResources(this, resId)?.let {
            tolgee.instant(key = it, parameters = TolgeeMessageParams.Indexed(*formatArgs))
        } ?: this.getString(resId, *formatArgs)
}

/**
 * Returns a localized formatted string from [Tolgee] cache or the application's package's
 * default string table, substituting the format arguments as defined in
 * [java.util.Formatter] and [java.lang.String.format].
 *
 * @param resId Resource id for the format string
 * @param formatArgs The format arguments that will be used for
 *                   substitution.
 * @return The string data associated with the resource, formatted and
 *         stripped of styled text information.
 */
fun Context.getStringInstant(@StringRes resId: Int, vararg formatArgs: Any): String {
    val instance = Tolgee.instance ?: return this.getString(resId, *formatArgs)
    return this.getStringInstant(instance, resId, *formatArgs)
}

fun Resources.getQuantityStringInstant(tolgee: Tolgee, @PluralsRes resId: Int, quantity: Int): String {
    return (tolgee as? TolgeeAndroid)?.pluralInstant(this, resId, quantity)
        ?: TolgeeAndroid.getKeyFromResources(this, resId)?.let {
            tolgee.instant(key = it, TolgeeMessageParams.Indexed(quantity))
        } ?: this.getQuantityString(resId, quantity)
}

fun Resources.getQuantityStringInstant(tolgee: Tolgee, @PluralsRes resId: Int, quantity: Int, vararg formatArgs: Any): String {
    return (tolgee as? TolgeeAndroid)?.pluralInstant(this, resId, quantity, *formatArgs)
        ?: TolgeeAndroid.getKeyFromResources(this, resId)?.let {
            tolgee.instant(key = it, TolgeeMessageParams.Indexed(quantity, *formatArgs))
        } ?: this.getQuantityString(resId, quantity, *formatArgs)
}

fun Resources.getQuantityStringInstant(@PluralsRes resId: Int, quantity: Int): String {
    val instance = Tolgee.instance ?: return this.getQuantityString(resId, quantity)
    return this.getQuantityStringInstant(instance, resId, quantity)
}

fun Resources.getQuantityStringInstant(@PluralsRes resId: Int, quantity: Int, vararg formatArgs: Any): String {
    val instance = Tolgee.instance ?: return this.getQuantityString(resId, quantity, *formatArgs)
    return this.getQuantityStringInstant(instance, resId, quantity, *formatArgs)
}

fun Resources.getStringArrayInstant(tolgee: Tolgee, @ArrayRes resId: Int): Array<String> {
    val list = (tolgee as? TolgeeAndroid)?.stringArrayInstant(this, resId)
        ?: TolgeeAndroid.getKeyFromResources(this, resId)?.let {
            tolgee.stringArrayInstant(key = it)
        }
    return list?.ifEmpty { null }?.toTypedArray() ?: this.getStringArray(resId)
}

/**
 * Typealias representing a platform-specific implementation of the Tolgee class for Android.
 *
 * This alias maps `PlatformTolgee` to `TolgeeAndroid` on the Android platform, allowing platform
 * dependency abstraction in multi-platform projects. The actual implementation, `TolgeeAndroid`,
 * provides Android-specific utilities for managing translations and localization tasks using
 * string resources.
 */
actual typealias PlatformTolgee = TolgeeAndroid