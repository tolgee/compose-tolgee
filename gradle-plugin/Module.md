# Module Gradle Plugin

Gradle plugin that wires the Tolgee Kotlin compiler plugin into a build, rewriting existing
`getString()`, `getQuantityString()` and `stringResource()` calls to their Tolgee equivalents at
compile time — no source changes needed.

Exposes the `tolgee { compilerPlugin { android { } compose { } } }` configuration DSL for
choosing which transformations to apply.

See the [Gradle plugin README](https://github.com/tolgee/tolgee-mobile-kotlin-sdk/blob/master/gradle-plugin/README.md)
for the full option list.
