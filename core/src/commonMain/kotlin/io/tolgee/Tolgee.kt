package io.tolgee

import de.comahe.i18n4k.Locale
import de.comahe.i18n4k.forLocaleTag
import de.comahe.i18n4k.language
import de.comahe.i18n4k.toTag
import dev.datlag.tooling.async.suspendCatching
import io.ktor.client.*
import io.ktor.client.engine.*
import io.tolgee.Tolgee.Companion.systemLocale
import io.tolgee.api.TolgeeApi
import io.tolgee.cache.TranslationCache
import io.tolgee.common.PlatformTolgee
import io.tolgee.common.createPlatformTolgee
import io.tolgee.common.platformHttpClient
import io.tolgee.common.platformNetworkContext
import io.tolgee.common.platformStorage
import io.tolgee.model.TolgeeMessageParams
import io.tolgee.model.TolgeeManifest
import io.tolgee.model.TolgeeTranslation
import io.tolgee.model.translation.TranslationEmpty
import io.tolgee.storage.TolgeeStorageProvider
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * The `Tolgee` class serves as the primary interface for interacting with the Tolgee localization
 * and translation framework. It provides mechanisms for managing languages, locales, and translations
 * in a coroutine-safe and thread-safe manner, ensuring seamless integration of localization features
 * into Kotlin-based applications.
 *
 * This class maintains internal caches and reactive flows for efficient retrieval and updates of
 * translations and locales, prioritizing both performance and flexibility in multilingual environments.
 *
 * Core features include:
 * - Loading and caching of project languages and translations
 * - Locale management with support for reactive updates
 * - On-demand and immediate translation resolution
 *
 * Designed for applications requiring dynamic multilingual support, the `Tolgee` class ensures thread-safe
 * access patterns and handles asynchronous operations efficiently to maintain consistent behavior.
 *
 * Properties:
 * - `config`: Configuration settings for the Tolgee instance.
 *
 * Methods:
 * - `tFlow`: Processes and streams dynamic translations for a given key and parameters.
 * - `t`: Immediately resolves a translation for a given key and parameters.
 * - `preload`: Preloads languages and translations into memory for subsequent use.
 * - `setLocale`: Updates the current locale using various parameter types.
 */
open class Tolgee(
    open val config: Config
) {

    /**
     * A coroutine-safe mutex used to ensure thread safety when accessing
     * or modifying translations-related data.
     *
     * This mutex is mainly utilized to prevent race conditions and to
     * synchronize access to shared translation resources during operations
     * such as loading or caching translations.
     */
    private val translationsMutex = Mutex()

    /**
     * In-memory cache of loaded manifest.
     */
    private val cachedManifest = atomic<TolgeeManifest?>(null)

    /**
     * LRU cache for storing multiple translation instances.
     *
     * This cache stores `TolgeeTranslation` objects for different locales, allowing
     * switching between locales without reloading from network/storage.
     *
     * The cache size is configured via `config.contentDelivery.maxLocalesInMemory`:
     * - `null`: unlimited cache (no eviction)
     * - `1`: default (single locale)
     * - `2+`: multi-locale cache with LRU eviction
     *
     * The cache is updated during the `loadTranslations` function and is accessed
     * in the `currentTranslation` function to retrieve translations for a given locale.
     */
    private val translationCache: TranslationCache by lazy {
        TranslationCache(config.contentDelivery.maxLocalesInMemory)
    }

    /**
     * A reactive flow that holds the current locale used for translation operations.
     *
     * The `localeFlow` emits the current locale state and is used throughout the Tolgee instance
     * to ensure locale consistency across translations and other related operations.
     *
     * It is initialized lazily using the default locale specified in the configuration (`config.locale`).
     * Subscribers to this flow automatically receive updates whenever the locale changes,
     * ensuring dynamic reactivity for translation-dependent functionalities.
     */
    private val localeFlow by lazy {
        MutableStateFlow(config.locale)
    }

    /**
     * A flow that emits whenever translations change.
     *
     * This can be used in to reactively respond to translation changes.
     */
    val changeFlow by lazy {
        MutableSharedFlow<Unit>()
    }

    /**
     * Interface for listening to changes in Tolgee translations.
     *
     * This interface provides a callback mechanism for Java code and other environments
     * where Kotlin Flows might not be the preferred approach for handling asynchronous events.
     */
    interface ChangeListener {
        /**
         * Called when translations in Tolgee have changed.
         */
        fun onTranslationsChanged()
    }

    /**
     * Collection of registered change listeners.
     */
    private val changeListeners = mutableSetOf<ChangeListener>()

    /**
     * Registers a listener to be notified when translations change.
     *
     * @param listener The listener to register
     */
    fun addChangeListener(listener: ChangeListener) {
        changeListeners.add(listener)
    }

    /**
     * Unregisters a previously registered change listener.
     *
     * @param listener The listener to unregister
     * @return true if the listener was found and removed, false otherwise
     */
    fun removeChangeListener(listener: ChangeListener): Boolean {
        return changeListeners.remove(listener)
    }

    /**
     * Generates progressive fallback variations for a locale by removing components
     * from right to left following BCP 47 structure (language-script-region-variant).
     *
     * Examples:
     * - "zh-Hans-CN" → ["zh-Hans-CN", "zh-Hans", "zh"]
     * - "en-US" → ["en-US", "en"]
     * - "sr-Cyrl" → ["sr-Cyrl", "sr"]
     * - "en" → ["en"]
     *
     * @param locale The locale to generate fallbacks for
     * @return List of locales in fallback order (most specific to least specific)
     */
    private fun generateLocaleFallbacks(locale: Locale): List<Locale> {
        val localeTag = locale.toTag("-")
        val components = localeTag.split("-")

        val fallbacks = mutableListOf<Locale>()

        // Start with the full locale
        fallbacks.add(locale)

        // Generate intermediate variations by removing components from right to left
        for (i in components.size - 1 downTo 2) {
            val fallbackTag = components.subList(0, i).joinToString("-")
            fallbacks.add(forLocaleTag(fallbackTag))
        }

        // Add base language if not already included (when components.size > 1)
        if (components.size > 1) {
            fallbacks.add(forLocaleTag(components[0]))
        }

        return fallbacks
    }

    /**
     * Resolves the most appropriate available locale from the given locale.
     *
     * Resolution strategy:
     * 1. Try exact locale match (e.g., "zh-Hans-CN" → "zh-Hans-CN")
     * 2. Try intermediate variations by progressively removing components:
     *    - "zh-Hans-CN" → "zh-Hans"
     *    - "zh-Hans" → "zh"
     * 3. Use the default language if configured
     *
     * The fallback process follows BCP 47 locale tag structure, removing
     * rightmost components (variant, region, script) one at a time until
     * a match is found.
     *
     * Examples:
     * - "zh-Hans-CN" → "zh-Hans-CN" → "zh-Hans" → "zh" → default
     * - "en-Latn-US" → "en-Latn-US" → "en-Latn" → "en" → default
     * - "sr-Cyrl" → "sr-Cyrl" → "sr" → default
     * - "en-US" → "en-US" → "en" → default
     *
     * Available locales are determined from:
     * 1. `config.availableLocales` (if manually specified)
     * 2. `loadedManifest.value.availableLocales` (if fetched from CDN)
     * 3. If neither is available, fallback is disabled (returns input locale as-is)
     *
     * @param locale The desired locale to resolve. Can be null.
     * @return The resolved locale, or null if the input is null.
     */
     protected fun resolveLocale(locale: Locale?): Locale? {
        if (locale == null) return null

        // Get available locales from config or loaded manifest
        val availableLocales = config.availableLocales
            ?: cachedManifest.value?.availableLocales

        if (availableLocales == null) {
            // Available locales not found in either config or manifest,
            // fallback mechanism disabled - return input locale as-is
            return locale
        }

        // Build a map keyed by lowercase tag for case-insensitive matching.
        // Returns the original available locale to preserve the exact casing
        // needed for CDN file paths.
        val localesByTag = availableLocales.associateBy { it.toTag("-").lowercase() }

        // Generate progressive fallback variations
        val fallbackCandidates = generateLocaleFallbacks(locale)

        // Try each fallback candidate in order (case-insensitive)
        for (candidate in fallbackCandidates) {
            val matchedLocale = localesByTag[candidate.toTag("-").lowercase()]
            if (matchedLocale != null) {
                return matchedLocale
            }
        }

        // Final fallback: Use default language if configured
        return config.defaultLanguage
    }

    /**
     * Loads manifest about available locales from the CDN or cache.
     *
     * This method fetches the manifest file which contains information
     * about which locales are available in the project. The manifest is used
     * for locale fallback (e.g., falling back from "en-US" to "en").
     *
     * This method is thread-safe but does NOT use mutex locking since:
     * - Multiple concurrent loads are acceptable (idempotent operation)
     * - The atomic update ensures thread safety
     * - Avoids blocking translation loading
     */
    private suspend fun loadManifest() {
        // Skip if manually configured
        if (config.availableLocales != null) return

        // Skip if already loaded
        if (cachedManifest.value != null) return

        withContext(config.network.context) {
            // Try to fetch fresh manifest from CDN
            val manifest = TolgeeApi.getManifest(
                client = config.network.client,
                config = config
            )

            cachedManifest.value = manifest
            changeFlow.emit(Unit)
            withContext(Dispatchers.Main) {
                changeListeners.forEach { listener ->
                    listener.onTranslationsChanged()
                }
            }
        }
    }

    /**
     * Loads translations for a given locale or the default locale if none is specified.
     *
     * This method attempts to retrieve the translation either from the cache or by querying
     * the Tolgee API. Newly retrieved translations are cached for future use according to
     * the configured cache size limit.
     *
     * Thread safety is ensured using a mutex lock.
     *
     * @param locale The target locale to load translations for. Defaults to the current value from `localeFlow`.
     * @return The loaded or cached translation.
     */
    private suspend fun loadTranslations(locale: Locale?) = translationsMutex.withLock {
        val localeTag = locale?.toTag("-")?.ifBlank { null }

        // Check cache first
        if (localeTag != null) {
            translationCache.get(localeTag)?.let {
                return@withLock it
            }
        }

        // Load from network/storage
        withContext(config.network.context) {
            val translation = TolgeeApi.getTranslations(
                client = config.network.client,
                config = config,
                currentLanguage = localeTag,
            )

            // Cache the loaded translation (skip TranslationEmpty)
            if (localeTag != null && translation !is TranslationEmpty) {
                translationCache.put(localeTag, translation)
            }

            // Emit change events
            changeFlow.emit(Unit)
            withContext(Dispatchers.Main) {
                changeListeners.forEach { listener ->
                    listener.onTranslationsChanged()
                }
            }

            return@withContext translation
        }
    }

    /**
     * Retrieves the currently cached translation that corresponds to the provided locale,
     * or defaults to the existing cache if no locale is explicitly provided.
     *
     * @param locale The locale for which the translation is being requested. If null, uses the default or current value from `localeFlow`.
     * @return The `TolgeeTranslation` instance matching the provided locale, or null if no applicable translation is found.
     */
    private fun currentTranslation(locale: Locale?): TolgeeTranslation? {
        val localeTag = locale?.toTag("-")?.ifBlank { null } ?: return null

        return translationCache.get(localeTag)?.takeIf {
            it.hasLocale(locale)
        }
    }

    /**
     * Updating Tolgee translation for a key with parameters.
     *
     * Respects locale changes from [setLocale].
     */
    @JvmOverloads
    @OptIn(ExperimentalCoroutinesApi::class)
    open fun tFlow(
        key: String,
        parameters: TolgeeMessageParams = TolgeeMessageParams.None
    ): Flow<String> = localeFlow.mapLatest { locale ->
        loadManifest()
        val locale = resolveLocale(locale)
        val translation = currentTranslation(locale) ?: suspendCatching {
            loadTranslations(locale)
        }.getOrNull() ?: currentTranslation(locale) ?: return@mapLatest t(key, parameters)

        return@mapLatest translation.localized(key, parameters, locale)
    }.filterNotNull()

    @OptIn(ExperimentalCoroutinesApi::class)
    open fun tArrayFlow(
        key: String
    ): Flow<List<String>> = localeFlow.mapLatest { locale ->
        loadManifest()
        val locale = resolveLocale(locale)
        val translation = currentTranslation(locale) ?: suspendCatching {
            loadTranslations(locale)
        }.getOrNull() ?: currentTranslation(locale) ?: return@mapLatest tArray(key)

        return@mapLatest translation.stringArray(key, locale)
    }.filterNotNull()

    /**
     * Immediate Tolgee translation for a key with parameters.
     *
     * **Requires** calling [preload] or [tFlow] before.
     * (Otherwise) May return null if no translations are loaded at time calling.
     *
     * Respects only the locale at time calling.
     */
    @JvmOverloads
    open fun t(
        key: String,
        parameters: TolgeeMessageParams = TolgeeMessageParams.None
    ): String? {
        val locale = resolveLocale(localeFlow.value)
        val translation = currentTranslation(locale) ?: return null

        return translation.localized(key, parameters, locale)
    }

    open fun tArray(
        key: String
    ): List<String> {
        val locale = resolveLocale(localeFlow.value)
        val translation = currentTranslation(locale) ?: return emptyList()

        return translation.stringArray(key, locale)
    }

    /**
     * Preloads the required languages and their translations for the current Tolgee instance.
     *
     * This method ensures that both the list of available project languages and their
     * corresponding translations are loaded into memory. It performs these operations atomically
     * by utilizing mutex locks to prevent concurrent modifications.
     *
     * Must be called before accessing translation functionalities such as [t] to ensure
     * that translations are available and up-to-date.
     *
     * This method is coroutine-safe and utilizes structured concurrency to manage asynchronous
     * operations.
     */
    open suspend fun preload() {
        suspendCatching {
            loadManifest()
            loadTranslations(resolveLocale(localeFlow.value))
        }
    }

    /**
     * Preloads all available languages and their translations into memory.
     *
     * This method loads translations for all locales defined in the manifest or configuration.
     * Translations are loaded into the LRU cache according to the configured cache size limit
     * (see [Config.ContentDelivery.maxLocalesInMemory]).
     *
     * The current locale is always loaded last to ensure it remains in the cache even when
     * the cache size is limited and other locales might be evicted due to LRU policy.
     *
     * Use cases:
     * - Applications that support frequent locale switching
     * - Offline-first applications that want to cache multiple languages
     * - Improving performance by preloading translations at app startup
     *
     * Individual locale loading failures are silently ignored, allowing other locales to load.
     *
     * @see preload For loading only the current locale
     */
    open suspend fun preloadAll() {
        suspendCatching {
            loadManifest()

            val availableLocales = config.availableLocales
                ?: cachedManifest.value?.availableLocales
                ?: emptyList()

            val currentLocale = resolveLocale(localeFlow.value)
            val currentLocaleTag = currentLocale?.toTag("-")?.lowercase()
            val otherLocales = availableLocales.filter { it.toTag("-").lowercase() != currentLocaleTag }

            otherLocales.forEach { locale ->
                suspendCatching {
                    loadTranslations(locale)
                }
            }

            if (currentLocale != null) {
                suspendCatching {
                    loadTranslations(currentLocale)
                }
            }
        }
    }

    /**
     * Sets the current locale for the Tolgee instance, updating it in the reactive locale flow.
     *
     * @param locale The locale to be set for translations and related operations.
     */
    @JvmOverloads
    open fun setLocale(locale: Locale) = localeFlow.updateAndGet { locale } ?: locale

    /**
     * Adjusts the current locale used for translations.
     *
     * @param localeTag A string representation of the desired locale.
     */
    @JvmOverloads
    open fun setLocale(localeTag: String) = setLocale(forLocaleTag(localeTag))

    /**
     * Gets the current locale for the Tolgee instance.
     *
     * Falls back to [systemLocale] if non set.
     */
    open fun getLocale() = localeFlow.value ?: systemLocale

    /**
     * The list of available locales from the manifest or configuration.
     *
     * Returns the locales configured via [Config.Builder.availableLocales], or if not
     * manually configured, the locales fetched from the CDN manifest.
     *
     * Returns null if the manifest hasn't been loaded yet and no manual config was provided.
     * Call [preload] first to ensure the manifest is loaded.
     *
     * For reactive updates, combine with [changeFlow]:
     * ```kotlin
     * tolgee.changeFlow.collect {
     *     val locales = tolgee.availableLocales
     * }
     * ```
     */
    open val availableLocales: List<Locale>?
        get() = config.availableLocales ?: cachedManifest.value?.availableLocales

    /**
     * The list of available locale tags (e.g., `["en", "fr", "de"]`) from the manifest or configuration.
     *
     * Returns null if the manifest hasn't been loaded yet and no manual config was provided.
     * Call [preload] first to ensure the manifest is loaded.
     *
     * @see availableLocales
     */
    open val availableLocaleTags: List<String>?
        get() = config.availableLocales?.map { it.toTag("-") }
            ?: cachedManifest.value?.locales

    /**
     * Represents the configuration used for API integration and content management.
     *
     * @property locale The target locale used for translations or project-specific setup.
     * @property network The network configuration used for executing HTTP requests and managing concurrency.
     * @property contentDelivery The CDN configuration for accessing and formatting content dynamically.
     */
    @ConsistentCopyVisibility
    data class Config internal constructor(
        val locale: Locale?,
        val network: Network,
        val contentDelivery: ContentDelivery,
        val availableLocales: List<Locale>?,
        val defaultLanguage: Locale?,
    ) {

        /**
         * Provides a builder pattern for constructing a `Config` instance with customizable properties.
         * Supports fluent method chaining to set various parameters such as API keys, URLs, project ID,
         * locale, network configuration, and CDN settings.
         */
        class Builder {

            /**
             * Represents the locale used for translations or language-specific configurations.
             * This variable holds a `Locale` object that defines the language and region settings
             * for the associated configuration or operation.
             *
             * A null value indicates that no specific locale has been set, and the default
             * locale behavior should be applied if required.
             */
            var locale: Locale? = systemLocale

            /**
             * A list of available locales. If specified, the app won't try to fetch a manifest from the server.
             * Instead, it will use the provided list of locales. Can be used to save on network requests.
             *
             * This list is used when determining the fallback language for translations.
             * The SDK performs progressive fallback through intermediate locale variations:
             * - If "zh-Hans-CN" doesn't exist, tries "zh-Hans"
             * - If "zh-Hans" doesn't exist, tries "zh"
             * - Finally uses the default language if configured
             *
             * If we don't have a list of available locales and manifest fetching fails, the fallback
             * mechanism will be disabled and only exactly matching locale will be used.
             */
            var availableLocales: List<Locale>? = null

            /**
             * The default language to use as a final fallback when the requested locale
             * and its base language are not available. This ensures that users with
             * unsupported languages receive translations from the CDN (in the default language)
             * instead of falling back to native bundled translations.
             *
             * Example: If a user has locale "zh-CN" and it's not available, but "en" is set
             * as the default language, the app will fetch and display English translations
             * from the CDN rather than using bundled translations.
             *
             * If null (default), the fallback chain ends with returning null from resolveLocale(),
             * which eventually leads to using TranslationEmpty and bundled translations.
             */
            var defaultLanguage: Locale? = null

            /**
             * Represents the network configuration used within the `Builder`.
             * This property defines the HTTP client and coroutine context used for executing network operations.
             * Can be customized directly by setting a `Network` instance or using a builder lambda function.
             *
             * The default value is an instance of `Network` initialized with platform-specific defaults.
             *
             * @see Network
             * @see Network.Builder
             */
            var network: Network = Network()

            /**
             * Represents the content delivery network (CDN) configuration associated with the builder.
             *
             * This variable holds a `CDN` instance that defines the CDN URL and formatting strategy
             * for handling translations or related content. The configuration can be directly assigned
             * or built using a fluent API provided by the `CDN.Builder` class.
             *
             * By default, this variable is initialized to a new instance of `CDN` with default values.
             * It can be customized using the `cdn(cdn: CDN)` or `cdn(builder: CDN.Builder.() -> Unit)` methods.
             *
             * The value of this property is incorporated into the resulting configuration when the
             * `build()` method is invoked.
             */
            var contentDelivery: ContentDelivery = ContentDelivery()

            /**
             * Sets the locale configuration for the builder and returns the instance for further customization.
             *
             * @param locale The locale to be set for the builder.
             */
            fun locale(locale: Locale) = apply {
                this.locale = locale
            }

            /**
             * Sets the locale for the configuration using the provided locale string.
             *
             * @param localeTag The locale string in the format of a language tag (e.g., "en", "fr", "es").
             */
            fun locale(localeTag: String) = locale(forLocaleTag(localeTag))

            /**
             * Sets the available locales configuration for the builder and returns the instance for further customization.
             *
             * @param locales A list of locales to be set for the configuration.
             */
            fun availableLocales(locales: List<Locale>) = apply {
                this.availableLocales = locales
            }

            /**
             * Sets the available locales configuration for the builder and returns the instance for further customization.
             *
             * @param locales A list of locales to be set for the configuration.
             */
            fun availableLocales(vararg locales: Locale) = apply {
                this.availableLocales = locales.toList()
            }

            /**
             * Sets the available locales configuration for the builder and returns the instance for further customization.
             *
             * @param localeTags A list of locale strings in the format of a language tag (e.g., "en", "fr", "es").
             */
            fun availableLocaleTags(localeTags: List<String>) = availableLocales(localeTags.map(::forLocaleTag))

            /**
             * Sets the available locales configuration for the builder and returns the instance for further customization.
             *
             * @param localeTags A list of locale strings in the format of a language tag (e.g., "en", "fr", "es").
             */
            fun availableLocaleTags(vararg localeTags: String) = availableLocales(localeTags.map(::forLocaleTag))

            /**
             * Sets the default language to use as a final fallback when the requested locale is not available.
             *
             * @param locale The locale to use as the default fallback language.
             */
            fun defaultLanguage(locale: Locale) = apply {
                this.defaultLanguage = locale
            }

            /**
             * Sets the default language to use as a final fallback when the requested locale is not available.
             *
             * @param localeTag A locale string in the format of a language tag (e.g., "en", "fr", "es").
             */
            fun defaultLanguage(localeTag: String) = defaultLanguage(forLocaleTag(localeTag))

            /**
             * Configures the network settings for the builder.
             *
             * @param network The network configuration used for HTTP client requests and coroutine context.
             */
            fun network(network: Network) = apply {
                this.network = network
            }

            /**
             * Configures the network settings for the builder using the provided configuration block.
             *
             * @param builder A lambda function used to configure the `Network.Builder`. This allows customization of
             * the HTTP client, coroutine context, or other network-related settings.
             */
            fun network(builder: Network.Builder.() -> Unit) = apply {
                this.network = Network.Builder().apply(builder).build()
            }

            /**
             * Sets the CDN configuration for the Builder instance.
             *
             * @param contentDelivery The CDN configuration to be applied. This parameter defines the content delivery network
             *            properties such as URL and formatting strategy used in the configuration.
             * @return The Builder instance with the configured CDN, allowing for method chaining.
             */
            fun contentDelivery(contentDelivery: ContentDelivery) = apply {
                this.contentDelivery = contentDelivery
            }

            /**
             * Configures the CDN settings for the builder.
             *
             * This method applies the provided configuration to the CDN by using the `CDN.Builder` class.
             * It allows the caller to set various properties for the CDN, such as URL or formatter, and
             * then builds the resulting CDN instance. The method enhances the builder's setup by enabling
             * configuration of CDN-related attributes.
             *
             * @param builder A lambda with a receiver of type `CDN.Builder` that defines the configuration
             *                for the CDN instance. Inside this lambda, you can call the available methods
             *                on `CDN.Builder` to set up the desired CDN properties.
             */
            fun contentDelivery(builder: ContentDelivery.Builder.() -> Unit) = apply {
                this.contentDelivery = ContentDelivery.Builder().apply(builder).build()
            }

            /**
             * Configures the content delivery settings for the Builder instance.
             *
             * Sets up a Content Delivery Network (CDN) by providing a URL and a configuration lambda.
             * The lambda uses a `CDN.Builder` receiver to apply various CDN settings, which are then
             * built and assigned to the builder.
             *
             * @param url The URL for the Content Delivery Network to be used.
             * @param builder A lambda with a receiver of type `CDN.Builder` to define the configuration
             *                for the CDN. Inside this lambda, you can customize various CDN properties.
             */
            @JvmOverloads
            fun contentDelivery(url: String, builder: ContentDelivery.Builder.() -> Unit = { }) = apply {
                this.contentDelivery = ContentDelivery.Builder().url(url).apply(builder).build()
            }

            /**
             * Builds and returns a new instance of the Config class using the provided builder properties.
             *
             * @return A Config instance populated with the values set in the Builder.
             */
            fun build(): Config = Config(
                locale = locale,
                availableLocales = availableLocales,
                network = network,
                contentDelivery = contentDelivery,
                defaultLanguage = defaultLanguage,
            )
        }

        /**
         * Represents a network configuration with an HTTP client and coroutine context.
         * This network configuration is used for making HTTP requests and specifying the coroutine context
         * under which the requests will be executed.
         *
         * @property client The HTTP client used for performing network requests.
         * @property context The coroutine context used for managing concurrency in network operations.
         */
        @ConsistentCopyVisibility
        data class Network internal constructor(
            val client: HttpClient = platformHttpClient,
            val context: CoroutineContext = platformNetworkContext
        ) {

            /**
             * Builder class that provides a fluent API to configure and build a Network instance.
             * Allows the customization of the underlying HTTP client and the coroutine context used within the Network.
             */
            class Builder {
                /**
                 * Configurable HTTP client instance used for executing network requests.
                 * This property can be customized with a specific implementation of [HttpClient]
                 * or left as the default platform HTTP client.
                 */
                var client: HttpClient = platformHttpClient

                /**
                 * The coroutine context used for executing network operations.
                 * Defaults to the platform-specific network context if not explicitly set.
                 */
                var context: CoroutineContext = platformNetworkContext

                /**
                 * Configures the HTTP client to be used in the network builder.
                 *
                 * @param client The HTTP client instance to set.
                 */
                fun client(client: HttpClient) = apply {
                    this.client = client
                }

                /**
                 * Configures the client with the provided HTTP client engine.
                 *
                 * @param engine The HTTP client engine used to create a new `HttpClient`.
                 */
                fun client(engine: HttpClientEngine) = client(HttpClient(engine))

                /**
                 * Configures the HTTP client using the specified engine factory.
                 *
                 * @param engineFactory The factory for creating an instance of an HTTP client engine. The engine factory
                 *                      must implement the [HttpClientEngineFactory] interface with a type parameter
                 *                      bounded by [HttpClientEngineConfig].
                 */
                fun <T : HttpClientEngineConfig> client(engineFactory: HttpClientEngineFactory<T>) = client(HttpClient(engineFactory))

                /**
                 * Configures and creates an instance of HttpClient using the provided configuration block.
                 *
                 * @param config A lambda function used to configure the HttpClientConfig.
                 */
                fun client(config: HttpClientConfig<*>.() -> Unit) = client(HttpClient(config))

                /**
                 * Configures and creates an HttpClient instance using the provided engine and configuration block.
                 *
                 * @param engine The HttpClientEngine to be used by the HttpClient.
                 * @param config The configuration block for customizing the HttpClient setup.
                 */
                fun client(
                    engine: HttpClientEngine,
                    config: HttpClientConfig<*>.() -> Unit
                ) = client(HttpClient(engine, config))

                /**
                 * Configures and initializes an `HttpClient` with the specified engine factory and configuration block.
                 *
                 * @param engineFactory The factory used to create the HTTP client engine, which determines the underlying implementation.
                 * @param config A lambda function providing additional configuration for the created HTTP client.
                 */
                fun <T : HttpClientEngineConfig> client(
                    engineFactory: HttpClientEngineFactory<T>,
                    config: HttpClientConfig<T>.() -> Unit
                ) = client(HttpClient(engineFactory, config))

                /**
                 * Sets the CoroutineContext for the Builder.
                 *
                 * @param context The CoroutineContext to be used for the network operations.
                 */
                fun context(context: CoroutineContext) = apply {
                    this.context = context
                }

                /**
                 * Constructs and returns an instance of the Network class with the configured properties.
                 *
                 * @return A fully configured instance of the Network class.
                 */
                fun build(): Network = Network(
                    client = client,
                    context = context
                )
            }
        }

        /**
         * Represents a content delivery network (CDN) configuration used within the system.
         *
         * This class includes properties for defining a CDN URL and a specific formatter to process
         * translations or related content dynamically. It serves as part of a builder pattern for constructing
         * a configurable CDN setup.
         *
         * The provided `url` can be explicitly set, or a default one is generated using additional
         * properties like `baseUrl` and `id` in the Builder. It also supports flexible formatting strategies
         * by utilizing the sealed `Formatter` interface.
         */
        @ConsistentCopyVisibility
        data class ContentDelivery internal constructor(
            val url: String? = null,
            val path: (language: String) -> String = { "$it.json" },
            val storage: TolgeeStorageProvider? = platformStorage,
            val formatter: Formatter = Formatter.Sprintf,
            val manifestPath: String = path("manifest"),
            val maxLocalesInMemory: Int? = 1,
        ) {
            /**
             * A builder class for constructing instances of `CDN` with configurable properties.
             * This class provides a fluent API to set various attributes for `CDN` and
             * validates/computes URL properties as necessary.
             */
            class Builder {
                /**
                 * Represents the URL associated with the CDN configuration.
                 *
                 * This property is optional and can be explicitly set using the `url` function in the `Builder` class.
                 * If left blank or not provided, an alternative URL will be constructed based on the `baseUrl` and `id`.
                 * The resulting URL is trimmed and validated to ensure it is non-blank.
                 */
                var url: String? = null

                /**
                 * Defines the path generation logic for localization files within the CDN configuration.
                 *
                 * This variable is a lambda function that takes a language code as input and returns
                 * the corresponding file path as a string. The default implementation appends ".json"
                 * to the supplied language code to generate the path.
                 *
                 * @property language The language code for which the path is being generated (e.g., "en").
                 * @return The generated file path, typically in the format `<language>.json`.
                 */
                var path: (language: String) -> String = { "$it.json" }

                /**
                 * Represents the manifest file path within the CDN configuration.
                 *
                 * This variable holds the path to the manifest file within the CDN configuration.
                 * The default value is "manifest.json".
                 */
                var manifestPath: String? = null

                /**
                 * Represents the storage configuration for the Builder.
                 *
                 * This property allows the customization of the storage mechanism by providing an implementation of
                 * the `TolgeeStorageProvider` interface. The `TolgeeStorageProvider` interface defines methods for storing and retrieving
                 * data, enabling support for different storage backends.
                 *
                 * By default, it is initialized with `platformStorage`, which can be replaced with a custom implementation
                 * through the `storage(storage: TolgeeStorageProvider)` method in the Builder.
                 *
                 * @property storage The `TolgeeStorageProvider` instance used to handle the storage of data.
                 */
                var storage: TolgeeStorageProvider? = platformStorage

                /**
                 * Specifies the formatting strategy to be used for dynamic text translations.
                 *
                 * Determines how parameters within translation messages are rendered. The default value is
                 * `Formatter.ICU`, which adheres to the ICU MessageFormat syntax for flexibility in
                 * internationalization. The formatter can also be customized by providing an alternative
                 * `Formatter` implementation such as `Formatter.Sprintf`.
                 *
                 * Typically used within the `CDN.Builder` class to configure translation formatting behavior
                 * for the resulting `CDN` instance.
                 */
                var formatter: Formatter = Formatter.Sprintf

                /**
                 * Maximum number of locales to keep in memory cache.
                 *
                 * Uses LRU (Least Recently Used) eviction when the limit is reached.
                 * - `null`: unlimited cache (no eviction)
                 * - `1`: default (caches only one locale, same as current behavior)
                 * - `2+`: caches multiple locales with LRU eviction
                 *
                 * Each cached locale consumes memory proportional to its translation file size.
                 */
                var maxLocalesInMemory: Int? = 1

                /**
                 * Sets the URL for the CDN configuration.
                 *
                 * @param url The URL to be used for the CDN.
                 * @return The Builder instance with the updated URL.
                 */
                fun url(url: String) = apply {
                    this.url = url
                }

                /**
                 * Sets the path for the CDN configuration based on the provided function.
                 *
                 * This method allows customization of the path generation by accepting a
                 * lambda function that takes a language string and returns the corresponding
                 * path as a string.
                 *
                 * @param path A lambda function that generates a path string when provided with a language code.
                 * @return The Builder instance with the updated path, enabling method chaining.
                 */
                fun path(path: (language: String) -> String) = apply {
                    this.path = path
                }

                /**
                 * Sets the manifest path for the CDN configuration.
                 *
                 * @param manifestPath The manifest path to be used for the CDN.
                 * @return The Builder instance with the updated manifest path.
                 */
                fun manifestPath(manifestPath: String) = apply {
                    this.manifestPath = manifestPath
                }

                /**
                 * Configures the storage settings for the builder.
                 *
                 * This method allows the binding of a specific storage implementation
                 * with the builder configuration to manage data storage operations.
                 *
                 * @param storage The storage implementation of type `TolgeeStorageProvider`.
                 *                This parameter defines how data will be stored and retrieved.
                 * @return The Builder instance with the configured storage, enabling method chaining.
                 */
                fun storage(storage: TolgeeStorageProvider) = apply {
                    this.storage = storage
                }

                /**
                 * Sets the formatter to be used for formatting translation messages.
                 *
                 * @param formatter The formatter implementation to be applied, such as `Formatter.ICU` or `Formatter.Sprintf`.
                 *                   Determines the strategy for rendering translations with placeholders.
                 * @return The Builder instance, enabling method chaining.
                 */
                fun formatter(formatter: Formatter) = apply {
                    this.formatter = formatter
                }

                /**
                 * Sets the maximum number of locales to keep in memory cache.
                 *
                 * Uses LRU (Least Recently Used) eviction when the limit is reached.
                 * Each cached locale consumes memory proportional to its translation file size.
                 *
                 * **Use Cases:**
                 * - Multi-language apps with frequent locale switching
                 * - Apps where users switch between 2-3 preferred languages
                 *
                 * **Default:** 1 (caches only one locale)
                 *
                 * @param max Maximum number of locales to cache. Use `null` for unlimited caching,
                 *            or a positive integer (>= 1) for limited caching with LRU eviction.
                 * @return The Builder instance, enabling method chaining.
                 * @throws IllegalArgumentException if max < 1 (when not null)
                 *
                 * **Example:**
                 * ```kotlin
                 * contentDelivery {
                 *     maxLocalesInMemory(3)  // Cache up to 3 locales
                 * }
                 * ```
                 */
                fun maxLocalesInMemory(max: Int?) = apply {
                    require(max == null || max >= 1) {
                        "maxLocalesInMemory must be null (unlimited) or at least 1, but was $max"
                    }
                    this.maxLocalesInMemory = max
                }

                /**
                 * Builds and returns a configured `CDN` instance.
                 *
                 * The method constructs the `CDN` using the defined URL and formatter properties.
                 * It derives the URL based on the provided `url` property, or combines the `baseUrl`
                 * and `id` properties if the `url` is blank or absent. The resulting URL is
                 * trimmed and validated. If no valid URL can be built, it defaults to `null`.
                 *
                 * @return A `CDN` instance with the chosen configuration, including the resolved URL
                 *         and formatter.
                 */
                fun build(): ContentDelivery = ContentDelivery(
                    url = url?.ifBlank { null },
                    path = path,
                    manifestPath = manifestPath ?: path("manifest"),
                    storage = storage,
                    formatter = formatter,
                    maxLocalesInMemory = maxLocalesInMemory
                )
            }

            /**
             * Companion object for the CDN class, keeping for extension functions.
             */
            companion object
        }

        /**
         * Companion object for the Config class, keeping for extension functions.
         */
        companion object
    }

    /**
     * Represents a formatter interface used for defining different formatting strategies
     * for translations within the Tolgee platform.
     *
     * The formatter determines how translation messages with parameters are rendered.
     * Available implementations are `ICU` for International Components for Unicode (ICU) formatting
     * and `Sprintf` for sprintf-style formatting.
     */
    sealed interface Formatter {
        /**
         * Represents the ICU formatter for localization and translation processing.
         *
         * This formatter utilizes the ICU MessageFormat syntax to handle dynamic text translations,
         * supporting features such as pluralization, gender selection, and variable substitution.
         * It adheres to the standards defined by the Unicode Consortium, ensuring widespread
         * compatibility with internationalization needs.
         *
         * Can be used as the default formatter for translation operations requiring
         * structured and flexible message formatting.
         */
        data object ICU : Formatter
        /**
         * Represents the `Sprintf` formatting logic used for string localization and translation.
         *
         * This object defines a formatting approach where placeholders in a string are replaced with
         * corresponding parameter values. It adheres to the Sprintf-style syntax, which is commonly
         * used for parameterized string interpolation.
         *
         * Typically used in localization tools to provide dynamic translations based on input parameters and
         * context. The `Sprintf` formatter is part of the `Formatter` sealed interface, allowing for flexibility
         * in formatting logic, alongside other implementations like `ICU`.
         *
         * This formatter is often leveraged in scenarios where Sprintf-style patterns are preferred or required.
         *
         * Implements the `Formatter` sealed interface, enabling compatibility within systems or frameworks
         * expecting a common interface for formatting behaviors.
         */
        data object Sprintf : Formatter
    }

    /**
     * The companion object for the Tolgee class, providing static methods and properties
     * to manage the global state and initialization of Tolgee instances.
     */
    companion object {

        /**
         * Used for tracking plugin/integration usage.
         */
        internal const val TYPE_HEADER = "Compose Multiplatform"
        internal const val VERSION_HEADER = "1.0.0-alpha01"

        /**
         * Provides the locale of the system where the application is running.
         *
         * This property retrieves the system's default locale, allowing the application
         * to adapt to the language and regional settings of the host environment.
         *
         * It is generally used as a fallback or default setting for localization
         * when no specific locale is explicitly configured by the application.
         */
        @JvmStatic
        val systemLocale
            get() = de.comahe.i18n4k.systemLocale

        /**
         * Holds the atomic reference to the singleton instance of the `Tolgee` class.
         *
         * This property ensures thread-safe initialization and access to the Tolgee instance.
         * It is designed to be used alongside initialization methods to provide a central
         * point of access for translation and localization functionality.
         *
         * The value of `_instance` can be set or updated during the initialization process, typically
         * through methods such as `init` or `instanceOrInit`. The default value is `null` and should
         * only be updated with a properly instantiated `Tolgee` object.
         */
        private val _instance = atomic<PlatformTolgee?>(null)

        /**
         * Provides the singleton instance of the `Tolgee` class.
         *
         * The instance is accessed lazily and is nullable, meaning it may return `null` if
         * the `Tolgee` instance is not initialized. This is the primary entry point for
         * interacting with the `Tolgee` localization and translation functionalities.
         */
        @JvmStatic
        val instanceOrNull: PlatformTolgee?
            get() = _instance.value

        /**
         * Provides the singleton instance of the `Tolgee` class.
         *
         * Throws an `IllegalStateException` if the instance has not been initialized.
         */
        @JvmStatic
        val instance: PlatformTolgee
            get() = _instance.value ?: throw IllegalStateException("Tolgee instance not initialized")

        /**
         * Initializes the Tolgee framework with the specified configuration and sets it as the global instance.
         *
         * @param config The configuration object used to initialize the Tolgee instance.
         */
        @JvmStatic
        @JvmOverloads
        fun init(
            config: Config
        ) {
            if (_instance.value != null) {
                throw IllegalStateException("Tolgee is already initialized!")
            }
            val tolgee = createPlatformTolgee(config)
            if (!_instance.compareAndSet(null, tolgee)) {
                throw IllegalStateException("Tolgee is already initialized!")
            }
        }

        /**
         * Initializes the Tolgee instance with the provided configuration options and sets it as the global instance.
         *
         * @param builder A lambda function used to configure the builder for creating the Tolgee configuration.
         */
        @JvmStatic
        @JvmOverloads
        fun init(
            builder: Config.Builder.() -> Unit
        ) {
            init(Config.Builder().apply(builder).build())
        }

        /**
         * Initializes the Tolgee framework with the specified configuration and returns it.
         *
         * @param config The configuration object used to initialize the Tolgee instance.
         */
        @JvmStatic
        @JvmOverloads
        fun new(
            config: Config
        ) = createPlatformTolgee(config)

        /**
         * Initializes the Tolgee instance with the provided configuration options and returns it.
         *
         * @param builder A lambda function used to configure the builder for creating the Tolgee configuration.
         */
        @JvmStatic
        @JvmOverloads
        fun new(
            builder: Config.Builder.() -> Unit
        ) = new(Config.Builder().apply(builder).build())
    }
}
