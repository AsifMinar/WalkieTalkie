# Walkie Talkie

A push-to-talk walkie-talkie for Android that works over local Wi-Fi. No internet, no account, no server. Phones on the same Wi-Fi network (or one phone's hotspot) find each other automatically and talk directly, with end-to-end encryption.

**[Download the latest APK](https://github.com/AsifMinar/WalkieTalkie/releases/latest)**

## Features

- **Push to talk.** Hold the talk button to speak to everyone on your channel. Slide up to lock it hands-free, and tap to stop.
- **16 channels.** Only people on the same channel hear you.
- **Private calls.** Hold a person's name to talk to them alone, whatever channel they're on.
- **Automatic discovery.** Everyone running the app on the same network shows up within seconds.
- **Works on a hotspot.** No router? One phone turns on its hotspot and the others join it.
- **Nothing gets lost.** Messages that arrive while you're talking, or while someone else is, are held and played right after (up to 30 seconds of audio).
- **Always on (optional).** Keeps listening when the app is closed, with a notification you can turn off from.
- **Real radio feel.** Synthesized static, talk-permit chirps and roger beeps, which you can turn off.
- **Tiny.** About 60 KB, no libraries and no ads. It only uses the network to talk to other phones on your Wi-Fi.

## Requirements

- Android 12 or newer
- Two or more phones on the **same Wi-Fi network**, or connected to one phone's hotspot

## Install

1. Open the [latest release](https://github.com/AsifMinar/WalkieTalkie/releases/latest) on your phone and download the `.apk` file.
2. Open it. If Android asks, allow **Install unknown apps** for your browser or file manager.
3. Open Walkie Talkie and allow the **microphone** (to talk) and **notifications** (for background listening).

## How to use

| To do this | Do this |
| --- | --- |
| Turn the radio on or off | Tap the power button at the top right |
| Talk to your channel | Hold the talk button, then let go to finish |
| Talk without holding | Hold the talk button and slide up to lock; tap to stop |
| Change channel | Tap the **−** / **+** buttons |
| Talk to one person only | Hold their name in the people row |
| Change your name | Tap your name between the **−** and **+** buttons |
| Keep listening in the background | Turn on **Always on** |
| Mute radio sounds | Turn off **Sounds** |

A single transmission stops automatically after 3 minutes, like the time-out timer on a real radio.

**Can't see each other?** Make sure all phones are on the same network. Guest Wi-Fi and some public networks block devices from seeing each other; using a phone's hotspot usually solves it.

## Security and privacy

**What the app protects:**

- **End-to-end encryption.** Every pair of phones agrees on its own keys (ECDH P-256, then HKDF-SHA256), and all voice is encrypted with AES-128-GCM. Each listener gets their own encrypted copy.
- **Forward secrecy.** New keys are made every session, so recorded traffic can't be decrypted later, even if a phone is lost.
- **Private calls** are encrypted for one person only. Nobody else can decrypt them, even with the app on the same channel.
- **Tamper and replay protection.** Altered, forged or replayed packets are rejected.
- **Key proof.** A phone must prove it holds its key before anyone sends it audio, so copied announcements can't redirect your voice.
- **Hardened network code.** Strict packet parsing, rate limits and memory caps. Tests fuzz the parser with hundreds of thousands of malformed packets.
- **Nothing is recorded or stored.** Audio exists only in memory while it plays. There's no cloud backup, and traffic never leaves your Wi-Fi network.

**Limitations to know about:**

- **Anyone on the same Wi-Fi with this app can join your channel and hear it.** There's no group password. Use it on networks you trust, or on your own hotspot.
- **Identities aren't verified.** Names can be faked, and someone who controls the network itself (for example the router owner) could intercept a connection.
- **Some information is visible on the network.** Names, channels and when someone is talking can be seen by others on the Wi-Fi. The voice itself stays encrypted.

## Building from source

The app is built **without Gradle or Android Studio**, using the Android SDK command-line tools directly, by [`build.sh`](build.sh):

```
aapt -> javac -> ProGuard -> dx -> zipalign -> apksigner
```

### Tools needed (Linux or WSL)

- JDK 17 (`javac`, `keytool`)
- An Android SDK platform jar (`android-34.jar`)
- [ProGuard](https://www.guardsquare.com/proguard) 7.4
- `aapt`, `dalvik-exchange` (dx), `zipalign`, `apksigner` on your `PATH`

If your tools are installed somewhere else, point the script at them:

```bash
export SDK_JAR=/path/to/android-34.jar
export JAVAC=/path/to/javac
export PROGUARD=/path/to/proguard.jar
```

### Signing

Android only installs signed apps. The signing key is **not** in this repository. To build your own copy, create a key once:

```bash
keytool -genkeypair -keystore signing-key.jks -alias walkie \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then save its password in a file named `signing.env` next to `build.sh`:

```bash
WT_KEYSTORE_PASS=your-password-here
```

Both files are listed in `.gitignore`, so they never get committed. Back them up: you need the same key to install future builds as updates.

An APK signed with your own key can't update an installed copy from the official releases. Uninstall that one first.

### Build

```bash
./build.sh
```

The signed APK is written to `WalkieTalkie.apk`.

## Tests

The encryption, packet parser, trust rules and message queue are plain Java, so they're tested on a desktop JVM with no phone needed:

```bash
bash tests/run-tests.sh
```

This needs JDK 11 or newer.

## Project structure

```
AndroidManifest.xml     App permissions and components
build.sh                Build script (no Gradle)
proguard.pro            Shrinking and obfuscation rules
res/                    Icons, colors, theme
src/com/walkietalkie/wifi/
  MainActivity.java     Screen, permissions, name dialog
  RadioView.java        The whole radio UI, drawn on one canvas
  Geometry.java         Screen layout math
  RadioEngine.java      Networking: discovery, sending and receiving voice
  RadioService.java     Background listening (foreground service)
  Crypto.java           Key agreement and AES-GCM encryption
  Wire.java             Packet format and parser
  PeerTable.java        Who is online and which keys are trusted
  HoldQueue.java        Holds messages that arrive while the line is busy
  AudioOut.java         Speaker output
  Sfx.java              Synthesized radio sounds
  Prefs.java            Saved settings
tests/                  Desktop tests (security, trust rules, network simulation, screen layout)
```
