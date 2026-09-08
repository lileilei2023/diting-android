# 谛听 DiTing — Android

An AI recording-card companion: sync recordings off a hardware recorder,
transcribe them, understand them, and turn what was said into tasks that only act
after you say so.

Built from the Claude Design handoff in `project/` and the two device protocol
documents in `project/uploads/`.

## What is verified and what is not

> 2026-09-08 update: the app has since run on a real phone against a real MR20 and the live
> brain; see `STATUS.md` for what is verified now. The paragraphs below describe the state at
> hand-off and are kept for the build details.

**Verified here** — the pure-Kotlin modules build and their tests run on a plain
JVM with no Android SDK:

```
./gradlew :core:protocol:test :core:domain:test :core:ai:test
```

240 tests, all passing. They cover the MR20 wire protocol against a fake device
that speaks it, the Wi-Fi trailer handling across arbitrary packet boundaries,
the task gate state machine, the retention rules, the noise gates, and the LLM /
ASR clients against a mock HTTP server.

**Also verified** — `:app` compiles and packages. `./gradlew :app:assembleDebug`
produces `app/build/outputs/apk/debug/app-debug.apk` (~23 MB, `com.diting.app.debug`,
minSdk 26, targetSdk 35) against SDK platform 35 and build-tools 35.0.0. That
covers KSP (Room and Hilt code generation), the Kotlin compilation of all 22
Compose screens, Dagger's graph validation, and dexing.

**Still not verified** — nothing has *run*. The APK has never been installed on a
device or an emulator, so no screen has been rendered, no BLE session opened
against a real MR20, and no Room query executed. Compilation and a valid Dagger
graph rule out a large class of failures; they say nothing about behaviour.

The first-run path most likely to still be wrong is the one nothing here can
exercise: `DitingApp.onCreate` enqueues two periodic workers before any screen
exists, and `DeviceService` goes foreground the moment it is created. Both are
worth watching in `logcat` on the first launch.

## Getting an APK without an SDK

`.github/workflows/android.yml` builds the debug APK on every push and uploads
it as the `diting-debug-apk` artifact. Open the run, download it, and:

```bash
adb install -r app-debug.apk        # package: com.diting.app.debug
adb logcat -c && adb logcat | grep -iE "diting|AndroidRuntime"
```

It is signed with the debug key, so it installs but is not distributable.

This exists because the SDK and every androidx artifact come from
`dl.google.com`, and an environment that cannot reach that host cannot produce
an APK at all. A runner can, so the Android half of the build stays verifiable
from anywhere.

When a CI build fails, the reason is emitted as **check annotations**
(`.github/scripts/annotate_failures.py`) as well as appearing in the log. The
log archive is served from a storage host that not every environment can reach;
annotations come back from `api.github.com` with the check run, so a red build
is diagnosable wherever the API is.

## Building locally

```bash
# Point at your SDK, then:
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
```

Without an SDK, install one with `sdkmanager "platforms;android-35"
"build-tools;35.0.0" "platform-tools"`. Both the Android SDK and every androidx
artifact come from `dl.google.com` (`maven.google.com` is a 301 to the same
host), so a network policy that blocks it blocks the `:app` build entirely — the
core modules still build and test, which is what the conditional include in
`settings.gradle.kts` is for.

`settings.gradle.kts` only includes `:app` when it can find an SDK, which is what
lets the core modules build in environments without one. With no SDK the build
prints a note and skips it rather than failing to resolve the Android plugin.

The root `build.gradle.kts` runs the *same* SDK probe to decide whether to put
AGP on the buildscript classpath, and the two must agree. AGP has to be loaded
there rather than in `:app` alone: plugin scopes are children of the root
buildscript scope, so that is what makes AGP visible to the root-declared Kotlin
plugin. Declaring AGP only in `:app` puts it in a sibling scope and applying
`org.jetbrains.kotlin.android` fails with `NoClassDefFoundError:
com/android/build/gradle/api/BaseVariant` — which is invisible in any
environment that has no SDK to reach that code path with.

## Module layout

| Module | Depends on Android? | What it holds |
|---|---|---|
| `core:protocol` | no | MR20 BLE protocol, Farosh cloud client |
| `core:domain` | no | Task gates, retention, noise gates, memory graph, scenes |
| `core:ai` | no | ASR and LLM clients, prompts, structured-output recovery |
| `app` | yes | Room, BLE/Wi-Fi transports, services, 22 Compose screens |

The split is not ceremony. It is what makes the protocol testable: the hardest
logic in this codebase is the MR20 command grammar and the file-transfer state
machine, and both can be exercised against a fake device on a JVM.

## The two devices

**MR20** — a BLE recorder with a fully documented ASCII command protocol
(`MR20通信协议_20260721.xlsx`). Everything in the document is implemented:
pairing, recording control, directory and file enumeration, file sync over both
BLE and the device's own Wi-Fi AP, deletion, Wi-Fi state machine, MCU and Wi-Fi
OTA, bitrate, USB mass storage, time sync.

Three details in that protocol cause most of the bugs in ad-hoc implementations,
and each has a test:

- There are no correlation IDs, so exactly one command may be outstanding.
- The Wi-Fi transfer appends a 5-byte trailer (`BA 5A 02 8F 04`) that is not part
  of the file and routinely straddles two socket reads.
- File bytes arrive on a *different* GATT characteristic than the length
  announcement, so the first packets can land before the app has processed
  `AA_DEV&U&LEN`. The receiver is armed before the request is written.

**Farosh** — a cloud product (`Farosh_接口协议.xlsx`). The REST surface is
implemented, along with the three header conventions the spec calls out.

Its device-side Bluetooth is documented only as "RFCOMM/SPP, no custom framing" —
the actual frame format is not in the supplied document, so 谛听 reaches Farosh
recordings through the cloud API rather than over the air. **If you have that
frame spec, it is the missing piece for direct Farosh device support.**

The Farosh client is unverified against a live server: this repository has no
account. The envelope's success code and the undocumented request-body fields are
the two things most likely to need correcting on first contact.

## Configuring the AI layer

Nothing transcribes until a model is configured, under 我的 › 教小谛 › 模型与
Skill. Three independent slots:

- **转写** needs a service exposing `POST /audio/transcriptions` — a self-hosted
  Whisper, FunASR or WhisperX all work. The request asks for `verbose_json`; a
  server that ignores it still produces a transcript, but with no per-sentence
  timestamps there is nothing for "↩ 回到原声" to anchor to.
- **理解** and **Agent** speak OpenAI-compatible chat completions, which 千问
  (DashScope compatible-mode) and 豆包 (火山方舟) both expose.

Base URL, model and key are all editable, and the defaults are a starting point
rather than a guarantee — vendors move both, and an app that hard-codes them
becomes unusable the day they do. 豆包 in particular usually wants an inference
endpoint id (`ep-…`) rather than a model name.

Keys are held in `EncryptedSharedPreferences`, separately from the rest of the
configuration in DataStore.

## Design principles that are enforced in code, not just documented

- **One amber gate.** `TaskGate` is the only place task state changes. Exactly
  one state may touch a third-party service, and a test asserts that stays true.
  Adopting to an external destination cannot skip its second confirmation,
  because the transition itself refuses.
- **Cited means permanent.** Anything a task, insight or report cites is exempt
  from every retention tier. The flag is written at citation time and the sweep
  queries exclude it, so a bug in the sweeper's control flow cannot defeat it.
- **AI guesses are not facts.** New graph nodes arrive as `PROPOSED`. Only
  `CONFIRMED` nodes may generate a proactive card.
- **BP rewards closing loops, not recording.** Every scoring event is a
  confirmation, an adoption or a correction.

## Permissions

Checked at the point of use, in `DitingPermissions` — not once at launch. A
grant can be denied at the dialog, revoked later in Settings, or auto-revoked by
the system for an app left unused, and the platform does not fail these calls
softly: `startForeground` with `FOREGROUND_SERVICE_TYPE_MICROPHONE` throws
`SecurityException` without `RECORD_AUDIO`, and every scan and GATT call throws
without `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`. Each one kills the process.

`@SuppressLint("MissingPermission")` remains on the call sites, because the
check is one frame up and lint cannot see it. It is not a substitute for the
check — that was the bug.

## Known gaps

- Destination connectors (Calendar, Notion, Email, Lark, OpenClaw) are modelled
  and routed but not connected. The UI says so rather than showing a button that
  silently does nothing.
- The realtime transcription channel is represented in the recording screen's
  state but not implemented; recordings transcribe in batch.
- Insights and proactive cards are read and rendered from the database but
  nothing generates them yet — that is the memory-graph extraction pass.
- Voiceprint enrolment in onboarding collects scenes and hotwords; the voiceprint
  itself is not computed.
- No migrations yet. The version-1 schema is now exported to
  `app/schemas/com.diting.app.data.db.DitingDatabase/1.json` and checked in, which
  is what a future `Migration` will be diffed against — keep it in git.
  `AppModule` deliberately omits
  `fallbackToDestructiveMigration` — these are the user's recordings, and dropping
  them on a schema change is not an acceptable failure mode. Ship a `Migration`
  before changing the schema.
