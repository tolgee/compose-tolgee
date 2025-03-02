package dev.datlag.tolgee

import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import de.comahe.i18n4k.Locale
import de.comahe.i18n4k.forLocaleTag
import de.comahe.i18n4k.language
import dev.datlag.tolgee.api.TolgeeApi
import dev.datlag.tolgee.common.*
import dev.datlag.tolgee.common.createPlatformTolgee
import dev.datlag.tolgee.common.platformHttpClient
import dev.datlag.tolgee.common.platformNetworkContext
import dev.datlag.tolgee.model.TolgeeMessageParams
import dev.datlag.tolgee.model.TolgeeProjectLanguage
import dev.datlag.tolgee.model.TolgeeTranslation
import dev.datlag.tooling.async.suspendCatching
import io.ktor.client.*
import io.ktor.client.engine.*
import kotlinx.atomicfu.atomic
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
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
 * - `languages`: Retrieves a list of available languages, preferring the network source with a fallback to cache.
 * - `translation`: Processes and streams dynamic translations for a given key and parameters.
 * - `instant`: Immediately resolves a translation for a given key and parameters.
 * - `preload`: Preloads languages and translations into memory for subsequent use.
 * - `setLocale`: Updates the current locale using various parameter types.
 */
open class Tolgee(
    open val config: Config
) {

    /**
     * A mutex used to ensure thread safety when performing operations related to language management.
     *
     * This mutex is particularly utilized in coroutine-safe methods such as loading or accessing
     * project languages, guarding against concurrent modifications to shared resources like cached data.
     *
     * By using this mutex, the system ensures that only one coroutine at a time can access or
     * modify language-related data, maintaining consistency and preventing race conditions.
     */
    private val languagesMutex = Mutex()

    /**
     * Stores a cached set of `TolgeeProjectLanguage` objects for efficient access during operations.
     *
     * This property is intended to minimize redundant network calls by caching the list of project languages
     * after they are initially retrieved from the Tolgee API. It ensures that frequently used language data
     * is readily available, improving performance and reducing latency for subsequent requests.
     *
     * The cache is dynamically updated whenever the languages are reloaded, ensuring it remains consistent
     * with the project configuration. Thread safety is maintained through the use of mutex locks in the
     * associated methods.
     */
    private var cachedLanguages: ImmutableSet<TolgeeProjectLanguage> = persistentSetOf()

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
     * A thread-safe atomic reference that holds the currently cached translation instance.
     *
     * This property is used to store a `TolgeeTranslation` object representing the translations
     * retrieved for a specific locale or the default locale. It supports concurrent read and
     * write operations, ensuring synchronization and data integrity across threads or coroutines.
     *
     * The cached translation is updated during the `loadTranslations` function and is accessed
     * in the `currentTranslation` function to retrieve the most recent translation for a given locale.
     */
    private val cachedTranslation = atomic<TolgeeTranslation?>(null)

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
     * Loads project languages in a coroutine-safe manner using a mutex lock to ensure thread safety.
     *
     * This method attempts to retrieve languages from a cached list. If the cache is empty, it performs
     * a network request using the configured API client to fetch all project languages. The fetched
     * result is then cached for future use. Execution occurs within the defined coroutine context for
     * network operations.
     *
     * This function is primarily used to ensure the availability of project languages for translation
     * and localization-related tasks.
     */
    private suspend fun loadLanguages() = languagesMutex.withLock {
        cachedLanguages.ifEmpty { withContext(config.network.context) {
            TolgeeApi.getAllProjectLanguages(
                client = config.network.client,
                config = config
            ).also {
                cachedLanguages = it
            }
        } }
    }


    /**
     * Loads translations for a given locale or the default locale if none is specified.
     *
     * This method attempts to retrieve the translation either from the currently cached data
     * or by querying the Tolgee API. Newly retrieved translations are cached for future use.
     * Thread safety is ensured using a mutex lock.
     *
     * @param locale The target locale to load translations for. Defaults to the current value from `localeFlow`.
     */
    private suspend fun loadTranslations(
        locale: Locale? = localeFlow.value
    ) = translationsMutex.withLock {
        currentTranslation(locale) ?: withContext(config.network.context) {
            TolgeeApi.getTranslations(
                client = config.network.client,
                config = config,
                currentLanguage = locale?.language?.ifBlank { null },
            ).also {
                cachedTranslation.value = it
            }
        }
    }

    /**
     * Retrieves the currently cached translation that corresponds to the provided locale,
     * or defaults to the existing cache if no locale is explicitly provided.
     *
     * @param locale The locale for which the translation is being requested. If null, uses the default or current value from `localeFlow`.
     * @return The `TolgeeTranslation` instance matching the provided locale, or null if no applicable translation is found.
     */
    private fun currentTranslation(
        locale: Locale? = localeFlow.value,
    ): TolgeeTranslation? {
        return cachedTranslation.value?.takeIf {
            if (locale != null) {
                it.hasLocale(locale)
            } else {
                true
            }
        }
    }

    /**
     * Retrieves the list of available languages for the Tolgee instance in a coroutine-safe manner.
     *
     * The method attempts to load the languages from the network by calling [loadLanguages].
     * If the network operation fails or returns null, it falls back to the cached languages.
     *
     * This function ensures thread safety by utilizing structured concurrency and handles exceptions
     * gracefully using `suspendCatching`.
     *
     * @return A list of available languages or the locally cached languages if the retrieval fails.
     */
    @NativeCoroutines
    open suspend fun languages(): Collection<TolgeeProjectLanguage> = suspendCatching {
        loadLanguages()
    }.getOrNull() ?: cachedLanguages

    /**
     * Updating Tolgee translation for key with parameters.
     *
     * Respects locale changes from [setLocale].
     */
    @NativeCoroutines
    open fun translation(
        key: CharSequence
    ): Flow<String> = translation(key, TolgeeMessageParams.None)

    /**
     * Updating Tolgee translation for key with parameters.
     *
     * Respects locale changes from [setLocale].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @NativeCoroutines
    open fun translation(
        key: CharSequence,
        parameters: TolgeeMessageParams
    ): Flow<String> = localeFlow.mapLatest { locale ->
        val translation = currentTranslation() ?: suspendCatching {
            loadTranslations()
        }.getOrNull() ?: currentTranslation() ?: return@mapLatest null

        return@mapLatest translation.localized(key.toString(), parameters, locale)
    }.mapNotNull()

    /**
     * Immediate Tolgee translation for key with parameters.
     *
     * **Requires** calling [preload] or [translation] before.
     * (Otherwise) May return null if no translations are loaded at time calling.
     *
     * Respects only the locale at time calling.
     */
    @JvmOverloads
    open fun instant(
        key: CharSequence,
        parameters: TolgeeMessageParams = TolgeeMessageParams.None
    ): String? {
        val translation = currentTranslation() ?: return null

        return translation.localized(key.toString(), parameters, localeFlow.value)
    }

    /**
     * Preloads the required languages and their translations for the current Tolgee instance.
     *
     * This method ensures that both the list of available project languages and their
     * corresponding translations are loaded into memory. It performs these operations atomically
     * by utilizing mutex locks to prevent concurrent modifications.
     *
     * Must be called before accessing translation functionalities such as [instant] to ensure
     * that translations are available and up-to-date.
     *
     * This method is coroutine-safe and utilizes structured concurrency to manage asynchronous
     * operations.
     */
    @NativeCoroutines
    open suspend fun preload() {
        suspendCatching { loadLanguages() }
        suspendCatching { loadTranslations() }
    }

    /**
     * Sets the current locale for the Tolgee instance, updating it in the reactive locale flow.
     *
     * @param locale The locale to be set for translations and related operations.
     */
    open fun setLocale(locale: Locale) = localeFlow.updateAndGet { locale }

    /**
     * Adjusts the current locale used for translations.
     *
     * @param locale A string representation of the desired locale.
     */
    open fun setLocale(locale: String) = setLocale(forLocaleTag(locale))

    /**
     * Sets the current locale using the specified language configuration.
     *
     * This method updates the locale by converting the provided language configuration
     * to a `Locale` instance.
     *
     * @param language The `TolgeeProjectLanguage` instance representing the language configuration
     * to set as the current locale.
     */
    open fun setLocale(language: TolgeeProjectLanguage) = setLocale(language.asLocale())

    /**
     * Represents the configuration used for API integration and content management.
     *
     * @property apiKey The API key used for authentication when interacting with the backend.
     * @property apiUrl The base URL of the API server.
     * @property projectId The unique identifier for the project within the system.
     * @property locale The target locale used for translations or project-specific setup.
     * @property network The network configuration used for executing HTTP requests and managing concurrency.
     * @property contentDelivery The CDN configuration for accessing and formatting content dynamically.
     */
    @ConsistentCopyVisibility
    data class Config internal constructor(
        val apiKey: String?,
        val apiUrl: String,
        val projectId: String?,
        val locale: Locale?,
        val network: Network,
        val contentDelivery: ContentDelivery,
    ) {

        /**
         * Provides a builder pattern for constructing a `Config` instance with customizable properties.
         * Supports fluent method chaining to set various parameters such as API keys, URLs, project ID,
         * locale, network configuration, and CDN settings.
         */
        class Builder {
            /**
             * Represents the API key used for authentication with the service.
             * This property is nullable and can be set using the corresponding builder method.
             */
            var apiKey: String? = null

            /**
             * Represents the base URL of the API that the application will interact with.
             * This property can be customized to point to different environments or API endpoints.
             *
             * - If a blank or null value is set, the default API URL will be applied.
             * - Leading and trailing whitespaces are trimmed from the value before assignment.
             *
             * @property apiUrl The string representing the API base URL.
             */
            var apiUrl: String = DEFAULT_API_URL
                set(value) {
                    field = value.trim().ifBlank { null } ?: DEFAULT_API_URL
                }

            /**
             * Represents the project identifier used to associate the configuration with
             * a specific project. Typically, this identifier is provided by the user or
             * the system and is optional.
             *
             * The value is automatically trimmed of whitespace, and if it is blank, it
             * will be set to `null`.
             */
            var projectId: String? = null
                set(value) {
                    field = value?.trim()?.ifBlank { null }
                }

            /**
             * Represents the locale used for translations or language-specific configurations.
             * This variable holds a `Locale` object that defines the language and region settings
             * for the associated configuration or operation.
             *
             * A null value indicates that no specific locale has been set, and the default
             * locale behavior should be applied if required.
             */
            var locale: Locale? = null

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
             * Configures the API key for the builder.
             *
             * @param apiKey The API key to be used for authentication.
             */
            fun apiKey(apiKey: String) = apply {
                this.apiKey = apiKey
            }

            /**
             * Sets the API URL for the builder instance.
             *
             * @param url The base URL of the API to be used.
             */
            fun apiUrl(url: String) = apply {
                this.apiUrl = url
            }

            /**
             * Sets the project identifier for the configuration.
             *
             * @param projectId The unique identifier of the project. It can be null if no project id is specified.
             */
            fun projectId(projectId: String?) = apply {
                this.projectId = projectId
            }

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
             * @param locale The locale string in the format of a language tag (e.g., "en", "fr", "es").
             */
            fun locale(locale: String) = locale(forLocaleTag(locale))

            /**
             * Configures the locale for the builder using a `TolgeeProjectLanguage` instance.
             *
             * This function converts the given `TolgeeProjectLanguage` into a `Locale` using its `asLocale()`
             * method and applies the resulting `Locale` to the builder configuration.
             *
             * @param language The `TolgeeProjectLanguage` instance representing the language configuration
             * that will be converted into a `Locale` and applied to the builder.
             */
            fun locale(language: TolgeeProjectLanguage) = locale(language.asLocale())

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
             * @param cdn The CDN configuration to be applied. This parameter defines the content delivery network
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
                apiKey = apiKey,
                apiUrl = apiUrl,
                projectId = projectId,
                locale = locale,
                network = network,
                contentDelivery = contentDelivery,
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
            val formatter: Formatter = Formatter.ICU
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
                 * Represents the base URL used as a default for constructing CDN URLs.
                 *
                 * This variable holds the root URL which will be combined with additional
                 * path segments (like `id`) to generate the complete CDN URL if no specific
                 * URL is provided. By default, it is initialized to the value defined in
                 * `DEFAULT_BASE_URL`.
                 *
                 * Can be updated through the `baseUrl()` function in the `Builder` class
                 * to customize the base URL for a particular CDN instance during the build process.
                 */
                private var baseUrl: String = DEFAULT_BASE_URL

                /**
                 * Represents a unique identifier used to build the complete CDN URL.
                 *
                 * This variable is optional and serves as a customizable part of the URL construction
                 * process. If provided, it will be combined with the base URL to generate a unique
                 * CDN endpoint. Otherwise, the builder may rely on other URL inputs or defaults during
                 * the `build` operation.
                 *
                 * Used internally by the `Builder` class to support dynamic URL construction.
                 */
                private var id: String? = null

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
                var formatter: Formatter = Formatter.ICU

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
                 * Sets the base URL for the CDN configuration.
                 *
                 * @param url The base URL to be used for generating complete CDN URLs.
                 *            This value overrides the default base URL.
                 * @return The Builder instance for method chaining.
                 */
                fun baseUrl(url: String) = apply {
                    this.baseUrl = url
                }

                /**
                 * Sets the identifier that can be used as part of the CDN configuration.
                 *
                 * The specified ID is used in conjunction with the base URL to construct the final CDN URL.
                 * Providing a null or blank ID will not modify the CDN URL.
                 *
                 * @param id The identifier to be used for the CDN. Can be null.
                 */
                fun id(id: String?) = apply {
                    this.id = id
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
                    url = url?.ifBlank { null } ?: id?.let {
                        combineUrlParts(baseUrl, it).trim()
                    }?.ifBlank { null },
                    formatter = formatter
                )
            }

            /**
             * Companion object for the CDN class providing utility constants and helper functions.
             */
            companion object {
                /**
                 * The default base URL used for constructing CDN resource paths.
                 *
                 * This constant serves as the fallback value for the `baseUrl` property in the `Builder` class,
                 * providing a predefined CDN endpoint to ensure proper URL formation when no custom base URL
                 * is specified. It is used as part of URL construction logic for assets, localization files,
                 * or other resources managed via the CDN class.
                 *
                 * The value points to a static endpoint (`https://cdn.tolg.ee/`) and can be overridden by the
                 * consumer through the `Builder` API, allowing for flexibility in customizing the base URL
                 * for resource retrieval purposes.
                 */
                internal const val DEFAULT_BASE_URL = "https://cdn.tolg.ee/"

                /**
                 * Combines two parts of a URL into a single valid URL string.
                 *
                 * @param one The first part of the URL, which may or may not end with a forward slash.
                 * @param two The second part of the URL, which may or may not start with a forward slash.
                 * @return A single URL string created by joining the two parts with a single forward slash.
                 */
                private fun combineUrlParts(one: String, two: String): String {
                    val start = if (one.endsWith('/')) one else "$one/"
                    val end = if (two.startsWith('/')) two.substring(1) else two
                    return "$start$end"
                }
            }
        }

        /**
         * Companion object for the Config class, providing default configuration constants and shared functionality.
         */
        companion object {
            /**
             * The default base URL for the Tolgee API version 2.
             *
             * This constant is used as the default endpoint for communicating with the Tolgee server
             * in the absence of a custom API URL provided by the user. It represents the root URL for
             * accessing all API services provided by Tolgee.
             */
            internal const val DEFAULT_API_URL = "https://app.tolgee.io/v2/"
        }
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
         *
         * Use this property to retrieve the currently active `Tolgee` instance, or initialize
         * a new instance if required via supporting functions.
         */
        @JvmStatic
        val instance: PlatformTolgee?
            get() = _instance.value

        /**
         * Initializes the Tolgee framework with the specified configuration and optionally sets it as the global instance.
         *
         * @param global A boolean flag indicating whether the initialized instance should be set as the global instance.
         *               Defaults to true if the current global instance is null.
         * @param config The configuration object used to initialize the Tolgee instance.
         */
        @JvmStatic
        @JvmOverloads
        fun init(
            global: Boolean = _instance.value == null,
            config: Config
        ) = createPlatformTolgee(config).also {
            if (global) {
                _instance.value = it
            }
        }

        /**
         * Initializes the Tolgee instance with the provided configuration options.
         *
         * This method sets up the configuration for the Tolgee library and prepares it for use.
         * Configuration options are specified via a [Config.Builder] block, allowing
         * customization of different settings such as API URL, authentication, and other
         * Tolgee features.
         *
         * @param global A flag indicating whether to initialize this instance globally. Defaults
         *        to true if no instance has been initialized yet; otherwise, false.
         * @param builder A lambda function used to configure the builder for creating the Tolgee configuration.
         */
        @JvmStatic
        @JvmOverloads
        fun init(
            global: Boolean = _instance.value == null,
            builder: Config.Builder.() -> Unit
        ) = init(global, Config.Builder().apply(builder).build())

        /**
         * Returns the existing instance of Tolgee if it has been initialized; otherwise, initializes
         * a new instance using the provided configuration and optional global context.
         *
         * This method ensures that the Tolgee instance is either reused if it already exists,
         * or created and configured if it does not. It relies on a lazy initialization strategy.
         *
         * @param global Specifies whether the instance should be initialized in a global context.
         *               Defaults to `true` if no instance currently exists; otherwise, `false`.
         * @param config The configuration object that provides the necessary setup details for
         *               initializing the Tolgee instance.
         */
        @JvmStatic
        @JvmOverloads
        fun instanceOrInit(
            global: Boolean = _instance.value == null,
            config: Config
        ) = instance ?: init(global, config)

        /**
         * Retrieves the current instance of the `Tolgee` class if it exists; otherwise, initializes a new instance.
         *
         * This method checks if the global instance of the `Tolgee` class is already initialized. If not,
         * it initializes a new instance using the provided configuration builder.
         * The method ensures thread-safe initialization and allows customization of the `Tolgee`
         * configuration through the builder parameter.
         *
         * @param global A Boolean flag determining whether the instance should be initialized globally. Defaults to true if no instance exists.
         * @param builder A lambda function for building the `Config` used during initialization.
         */
        @JvmStatic
        @JvmOverloads
        fun instanceOrInit(
            global: Boolean = _instance.value == null,
            builder: Config.Builder.() -> Unit
        ) = instance ?: init(global, builder)
    }
}