package dev.datlag.tolgee.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TolgeeProjectLanguage(
    @SerialName("name") val name: String,
    @SerialName("tag") val tag: String,
    @SerialName("originalName") val originalName: String? = null,
    @SerialName("flagEmoji") val flagEmoji: String? = null,
    @SerialName("base") val base: Boolean
) {

    @Serializable
    internal data class PagedWrapper(
        @SerialName("languages") val languages: List<TolgeeProjectLanguage>
    )
}
