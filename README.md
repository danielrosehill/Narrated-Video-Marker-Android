# Narrated Video Marker (Android)

A video recorder for narrated walkthroughs that an AI agent will read later. It records
720p video, and while it records you tap **MARK** (or press a volume key) whenever the
thing you're talking about is in frame. Each video is saved with a small JSON sidecar of
mark timestamps.

Example: walk around the apartment saying "the printer needs a new cable" *(tap)*, "this
tap drips" *(tap)*. The [`narrated-video-context`](https://github.com/danielrosehill/Narrated-Video-To-Agent-Context)
plugin then turns the video and its marks into a clean transcript with one still per mark,
referenced inline, ready to hand to an agent.

## Use

1. First launch: grant camera and microphone access, then pick a folder. A folder your
   computer syncs (Syncthing, Drive, Nextcloud) saves a transfer step.
2. **● REC** to start. Talk, and tap **MARK** (or press either volume key) when the subject
   is on screen. Long-press **MARK** to undo the last mark.
3. **■ STOP**. The folder now has `NV_<yyyyMMdd_HHmmss>.mp4` and `NV_<…>.marks.json`.

Hold still for half a second around a mark. The consumer picks the sharpest frame
slightly *before* the press, because tapping the screen shakes the phone.

## Output

- Video: H.264/AAC MP4, 720p (falls back to the nearest available size), about 4 Mbit/s,
  so roughly 30 MB per minute. Rotation follows the phone at the moment REC is pressed.
- Sidecar: [`docs/marks-format.md`](docs/marks-format.md). The sidecar is rewritten on every
  mark, so a crash keeps the marks made so far (`"complete": false`).

## Install

Download the APK from [Releases](../../releases) and sideload it. Requires Android 10+.

## Build

CI builds on every push (`.github/workflows/build.yml`). Tags `v*` publish a release.
Locally: `cd android && ./gradlew assembleRelease` (Android SDK, JDK 17). Release signing
reads `SIGNING_KEYSTORE_PATH`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and
`SIGNING_KEY_PASSWORD` from the environment, and falls back to the debug key.

## Stack

Kotlin, CameraX 1.4.2 (`camera-video` Recorder, `Quality.HD`), Storage Access Framework
for the output folder, plain Android views. No network access, no analytics.

## License

MIT
