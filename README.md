# ORCA Player

An advanced Android video player for phones, tablets, foldables and Android TV: an MX Player-style local
player, a Stremio-compatible addon section, a downloader and one Addon Manager, all in the TROY Enhanced
Theme with teal as the default accent. English and Arabic, with full right-to-left layout.

ORCA Player ships with no content sources. Users add their own.

## Builds

| Flavor | Application ID | Distribution |
|---|---|---|
| standard | `com.troy.orcaplayer` | Google Play compliant |
| extended | `com.troy.orcaplayer.extended` | Sideload only, GPL-3.0 (adds CloudStream extensions) |

Debug builds add `.debug` so they install next to release builds.

```bash
./gradlew :app:assembleStandardDebug
```

The newest debug APK of `main` is on the **Latest debug build** pre-release. Tagged releases (`v0.N.0`)
are signed and published by the Release workflow. Until the `ORCA_KEYSTORE_*` secrets are added, releases
are signed with the committed **test key** (`app/test-release.keystore`, password `orcatest`), so they are
for testing only: a test-signed install has to be removed before installing a real-key build.

## Modules

| Module | What it holds |
|---|---|
| `:app` | Navigation shell, Hilt, flavors |
| `:core:design` | TROY Enhanced Theme tokens and base components |
| `:core:model` | Pure Kotlin models: PlayRequest, media keys, saving rule, layout models |
| `:core:settings` | Settings schema, DataStore, search, reset, versioned migrations |
| `:core:data` | Room database: profiles, resume positions |
| `:sources:api` | Pure Kotlin `ContentSource` interface |
| `:sources:cloudstream` | Extended flavor only |

## Phases

See `docs/releases/` for the report of each phase.
