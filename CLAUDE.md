# Narrated-Video-Marker-Android

Release tier: small (`v1.0`, `v1.1`, …).

- App: `android/`, single activity `MainActivity.kt`, package `com.danielrosehill.narratedvideomarker`.
- The sidecar format in `docs/marks-format.md` is a contract shared with the plugin repo
  `danielrosehill/Narrated-Video-To-Agent-Context` (`references/marks-format.md` there is a copy).
  Change both together. Bump `version` for any breaking change.
- Don't build locally on the laptop. Push and let CI build. Signing secrets are in the
  GitHub repo secrets (`SIGNING_*`), and the keystore is backed up in 1Password.
