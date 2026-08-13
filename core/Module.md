# Module Core

Kotlin Multiplatform base library providing runtime support for Tolgee translations, so you can
update copy over-the-air without shipping a new app version.

Handles locale resolution with progressive BCP 47 fallback, translation loading from the Tolgee
CDN, and caching (in-memory LRU plus platform-specific persistent storage). Supports both Sprintf
(Android SDK) and ICU message formats.

Start with `Tolgee.init { contentDelivery { url = "https://cdn.tolg.ee/your-cdn-url-prefix" } }`,
then read translations via `Tolgee.instance.t("key")` or observe them with `tFlow("key")`.

See the [Core module README](https://github.com/tolgee/tolgee-mobile-kotlin-sdk/blob/master/core/README.md)
for setup and platform-specific notes.
