package io.tolgee

import de.comahe.i18n4k.Locale
import de.comahe.i18n4k.forLocaleTag
import de.comahe.i18n4k.toTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResolveLocaleTest {

    /**
     * Testable subclass that exposes resolveLocale for testing.
     */
    private class TestableTolgee(config: Tolgee.Config) : Tolgee(config) {
        fun testResolveLocale(locale: Locale?): Locale? = resolveLocale(locale)
    }

    private fun createTolgee(
        availableLocales: List<Locale>,
        defaultLanguage: Locale? = null,
    ): TestableTolgee {
        val config = Tolgee.Config.Builder()
            .availableLocales(availableLocales)
            .apply { defaultLanguage?.let { defaultLanguage(it) } }
            .build()
        return TestableTolgee(config)
    }

    @Test
    fun exactMatchWorks() {
        val tolgee = createTolgee(listOf(forLocaleTag("en"), forLocaleTag("zh-Hans")))
        val result = tolgee.testResolveLocale(forLocaleTag("zh-Hans"))
        assertEquals("zh-Hans", result?.toTag("-"))
    }

    @Test
    fun caseInsensitiveMatchForScript() {
        val tolgee = createTolgee(listOf(forLocaleTag("en"), forLocaleTag("zh-Hans")))
        val result = tolgee.testResolveLocale(forLocaleTag("zh-hans"))
        assertEquals("zh-Hans", result?.toTag("-"))
    }

    @Test
    fun caseInsensitiveMatchForRegion() {
        val tolgee = createTolgee(listOf(forLocaleTag("en"), forLocaleTag("pt-BR")))
        val result = tolgee.testResolveLocale(forLocaleTag("pt-br"))
        assertEquals("pt-BR", result?.toTag("-"))
    }

    @Test
    fun fallbackFromRegionToBaseLanguage() {
        val tolgee = createTolgee(listOf(forLocaleTag("en"), forLocaleTag("pt")))
        val result = tolgee.testResolveLocale(forLocaleTag("pt-BR"))
        assertEquals("pt", result?.toTag("-"))
    }

    @Test
    fun fallbackFromScriptRegionToScript() {
        val tolgee = createTolgee(listOf(forLocaleTag("en"), forLocaleTag("zh-Hans")))
        val result = tolgee.testResolveLocale(forLocaleTag("zh-Hans-CN"))
        assertEquals("zh-Hans", result?.toTag("-"))
    }

    @Test
    fun fallsBackToDefaultLanguageWhenNoMatch() {
        val defaultLang = forLocaleTag("en")
        val tolgee = createTolgee(
            listOf(forLocaleTag("en"), forLocaleTag("fr")),
            defaultLanguage = defaultLang,
        )
        val result = tolgee.testResolveLocale(forLocaleTag("ja"))
        assertEquals("en", result?.toTag("-"))
    }

    @Test
    fun nullLocaleReturnsNull() {
        val tolgee = createTolgee(listOf(forLocaleTag("en")))
        assertNull(tolgee.testResolveLocale(null))
    }

    @Test
    fun returnsInputLocaleWhenNoAvailableLocales() {
        val config = Tolgee.Config.Builder().build()
        val tolgee = TestableTolgee(config)
        val locale = forLocaleTag("zh-Hans")
        val result = tolgee.testResolveLocale(locale)
        assertEquals("zh-Hans", result?.toTag("-"))
    }

    @Test
    fun unicodeExtensionFallsBackToBaseLanguage() {
        val tolgee = createTolgee(listOf(forLocaleTag("en"), forLocaleTag("es")))
        val result = tolgee.testResolveLocale(forLocaleTag("es-u-ms-metric"))
        assertEquals("es", result?.toTag("-"))
    }

    @Test
    fun unicodeExtensionExactMatchWhenAvailable() {
        val tolgee = createTolgee(listOf(forLocaleTag("en"), forLocaleTag("es-u-ms-metric")))
        val result = tolgee.testResolveLocale(forLocaleTag("es-u-ms-metric"))
        assertEquals("es-u-ms-metric", result?.toTag("-"))
    }

    @Test
    fun fallbackFromLanguageScriptRegionToScriptWhenAvailable() {
        val tolgee = createTolgee(listOf(forLocaleTag("en"), forLocaleTag("en-Latn")))
        val result = tolgee.testResolveLocale(forLocaleTag("en-Latn-US"))
        assertEquals("en-Latn", result?.toTag("-"))
    }

    @Test
    fun fallbackFromLanguageScriptRegionToRegionWhenScriptUnavailable() {
        val tolgee = createTolgee(listOf(forLocaleTag("en"), forLocaleTag("en-US")))
        val result = tolgee.testResolveLocale(forLocaleTag("en-Latn-US"))
        assertEquals("en-US", result?.toTag("-"))
    }

    @Test
    fun fallbackFromLanguageScriptRegionToRegionWhenOnlyRegionalAvailable() {
        val tolgee = createTolgee(listOf(forLocaleTag("zh-CN")))
        val result = tolgee.testResolveLocale(forLocaleTag("zh-Hans-CN"))
        assertEquals("zh-CN", result?.toTag("-"))
    }
}
