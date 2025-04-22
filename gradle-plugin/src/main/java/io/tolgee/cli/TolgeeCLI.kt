package io.tolgee.cli

import io.tolgee.common.fullPathSafely
import io.tolgee.common.handleOutput
import io.tolgee.model.CLIOutput
import io.tolgee.model.Format
import io.tolgee.model.pull.State
import io.tolgee.model.push.Mode
import dev.datlag.tooling.async.scopeCatching
import io.github.z4kn4fein.semver.Version
import io.github.z4kn4fein.semver.toVersion
import io.github.z4kn4fein.semver.toVersionOrNull
import org.gradle.api.logging.Logger
import java.io.File

internal object TolgeeCLI : Node() {

    private const val app = "tolgee"

    val supportedMinVersion = "2.0.0".toVersion()
    val installed: Boolean by lazy {
        version()?.let { v -> v >= supportedMinVersion } ?: false
    }

    fun version(): Version? = scopeCatching {
        NodeCommand(app)
            .arg("--version")
            .output()
            .stdout
    }.getOrNull()?.ifBlank { null }?.trim()?.toVersionOrNull(strict = false)

    /**
     * Pull translations with Tolgee CLI.
     *
     * @param apiKey Can be a Personal Access Token or a Project API Key. May be omitted if you are logged in.
     * @param projectId Project ID on the Tolgee server.
     * @param format Localization files format.
     * @param languages List of languages to pull. Leave unspecified to export all languages.
     * @param states List of translation states to include.
     * @return true if command was successful.
     */
    fun pull(
        apiUrl: String?,
        projectId: String?,
        apiKey: String?,
        format: Format?,
        path: File,
        config: File?,
        languages: Collection<String>?,
        states: Collection<State>?,
        namespaces: Collection<String>?,
        tags: Collection<String>?,
        excludeTags: Collection<String>?,
        output: CLIOutput,
        logger: Logger
    ): Boolean = installed && scopeCatching {
        NodeCommand(app)
            .arg("pull")
            .apply {
                if (!apiUrl.isNullOrBlank()) {
                    args("--api-url", apiUrl)
                }
                if (!apiKey.isNullOrBlank()) {
                    args("--api-key", apiKey)
                }
                if (!projectId.isNullOrBlank()) {
                    args("--project-id", projectId)
                }
                if (format != null) {
                    args("--format", format.value)
                }
            }
            .args("--path", path.path)
            .apply {
                if (!languages.isNullOrEmpty()) {
                    args("--languages", languages.joinToString(" "))
                }
                if (!states.isNullOrEmpty()) {
                    args("--states", states.joinToString(" "))
                }
                if (!namespaces.isNullOrEmpty()) {
                    args("--namespaces", namespaces.joinToString(" "))
                }
                if (!tags.isNullOrEmpty()) {
                    args("--tags", tags.joinToString(" "))
                }
                if (!excludeTags.isNullOrEmpty()) {
                    args("--exclude-tags", excludeTags.joinToString(" "))
                }
                config?.fullPathSafely()?.let {
                    args("--config", it)
                }
            }
            .stdout(output.io)
            .spawn()
            .waitWithOutput()
            .handleOutput(output, logger)
    }.getOrNull()?.status == 0

    fun push(
        apiUrl: String?,
        projectId: String?,
        apiKey: String?,
        format: Format?,
        mode: Mode,
        config: File?,
        languages: Collection<String>?,
        namespaces: Collection<String>?,
        output: CLIOutput,
        logger: Logger
    ): Boolean = installed && scopeCatching {
        NodeCommand(app)
            .arg("push")
            .apply {
                if (!apiUrl.isNullOrBlank()) {
                    args("--api-url", apiUrl)
                }
                if (!apiKey.isNullOrBlank()) {
                    args("--api-key", apiKey)
                }
                if (!projectId.isNullOrBlank()) {
                    args("--project-id", projectId)
                }
                if (format != null) {
                    args("--format", format.value)
                }
            }
            .args("--force-mode", mode.value)
            .apply {
                if (!languages.isNullOrEmpty()) {
                    args("--languages", languages.joinToString(" "))
                }
                if (!namespaces.isNullOrEmpty()) {
                    args("--namespaces", namespaces.joinToString(" "))
                }
                config?.fullPathSafely()?.let {
                    args("--config", it)
                }
            }
            .stdout(output.io)
            .spawn()
            .waitWithOutput()
            .handleOutput(output, logger)
    }.getOrNull()?.status == 0

}
