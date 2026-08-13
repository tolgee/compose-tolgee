# Module Compose

Compose integration for Tolgee, working with both Jetpack Compose on Android and Compose
Multiplatform.

Provides drop-in `stringResource`, `pluralStringResource` and `stringArrayResource` composables
that read from Tolgee and recompose automatically when translations or the locale change. When
Tolgee is not initialized they fall back to the standard Compose resources, so adding this module
cannot break an existing UI.

See the [Compose module README](https://github.com/tolgee/tolgee-mobile-kotlin-sdk/blob/master/compose/README.md)
for setup and usage examples.
