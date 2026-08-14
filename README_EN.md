<p align="center">
  <img src="./images/logo_rounded.png" alt="SoundMan logo" width="160" />
</p>

<h1 align="center">SoundMan</h1>

<p align="center">
  An LSPosed / Xposed module for per-app volume and output-device control.<br>
  It hosts active playback sessions in the system framework, applies each app's volume and route rules, and adds an entry on the HyperOS volume sidebar.
</p>

<p align="center">
  <a href="./README.md">中文</a> · <a href="https://github.com/killerprojecte/SoundMan">Project Home</a>
</p>

<p align="center">
  <a href="https://github.com/killerprojecte/SoundMan/releases"><img src="https://img.shields.io/github/v/release/killerprojecte/SoundMan?display_name=tag" alt="GitHub release"></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="GPLv3 license"></a>
  <a href="https://github.com/killerprojecte/SoundMan/issues"><img src="https://img.shields.io/github/issues/killerprojecte/SoundMan" alt="GitHub issues"></a>
  <a href="https://developer.android.com/"><img src="https://img.shields.io/badge/Android-16%2B-3DDC84?logo=android&logoColor=white" alt="Android"></a>
  <a href="https://github.com/LSPosed/LSPosed"><img src="https://img.shields.io/badge/Framework-LSPosed%20%2F%20Xposed-5C6BC0" alt="Framework"></a>
</p>

## Feature Overview

- Adjust volume independently for apps that are currently playing, from 0% to 100%.
- Pin an app to a specific output device, or follow the system's current output.
- Keep up to three independent output routes so different apps can use different hardware.
- Fall back to the system output when a pinned device disconnects, while keeping the original
  target.
- Open the same rules UI from the overlay panel or the HyperOS volume sidebar.
- Check activation state, version, build channel, and git branch on the module home page, and jump
  to GitHub.

## Main Features

### App Volume

- Lists only apps that are currently playing, instead of the full installed-app list.
- Persists each rule by package name. Volume is always clamped to 0..100.
- 100% means no extra attenuation. Lower values are applied by the system audio host to that app's
  players.
- Shows an empty state when nothing is playing.
- App list permission is required to show app names. The panel can request it if it is missing.

### Output Devices

- Follow system
    - Does not create UID device affinity. Uses the system's current output.
- Pinned device
    - Pins routing to confirmed device types such as built-in, wired headset, Bluetooth, and USB.
    - Device identity uses AudioSystem internal type and address. The display name is not part of
      matching.
- Independent multi-app output
    - At most three independent playback paths.
    - A single occupied device stays on the normal media path.
    - The second and third paths split Mix and pin each path to the selected hardware.
- Disconnect handling
    - When a pinned device disconnects, output falls back to follow-system.
    - The original target is kept so the rule can resume after reconnect.
- Pinning is disabled when a public device type has no confirmed internal mapping.

### Volume Panel Entry

- Module home
    - "Open volume panel" shows the rules UI in a translucent overlay.
    - Opening and closing use a centered scale-and-fade animation.
- HyperOS volume sidebar
    - Inserts a round SoundMan entry into the system volume sidebar.
    - Opening from the sidebar slides the panel in from the right. Tap empty space or back to close
      it.
- Overlay
    - Hosts the Compose panel with `SYSTEM_ALERT_WINDOW`.
    - Does not touch the global media stream. It only rewrites each app's own rules.

### Module Home

- Inactive warning
    - Shows a red banner only when the module is not activated, including the required scope.
    - Hides the status card when the module is active.
- About
    - App icon, name, and author.
    - Version codename, module version, build channel, and git branch.
    - The branch label drops the repository prefix and keeps the branch name.
- GitHub
    - Opens the corresponding repository.

## Module Scope

Default scope includes these target processes:

- `android`
- `com.android.systemui`

Also add any app that should be pinned to an independent output device. The in-process
device-affinity hook only runs for apps included in the module scope.

## Requirements

- Android 16 / API 36 or later.
- LSPosed / Xposed compatible environment.
- Xposed minimum version 93.
- The round volume-sidebar entry targets HyperOS System UI. Per-app volume and routing depend on
  `system_server`.
- Showing the overlay requires the "Display over other apps" permission.
- Showing app names requires app-list permission (`QUERY_ALL_PACKAGES` / `GET_INSTALLED_APPS`).

## Installation

1. Download and install the latest APK
   from [Releases](https://github.com/killerprojecte/SoundMan/releases).
2. Enable `SoundMan` in LSPosed or a compatible framework.
3. Make sure the module scope includes `android`, `com.android.systemui`, and any apps that need
   independent routing.
4. Reboot the device, or at least restart System Framework and System UI before use.
5. Open the module app, confirm it is activated, then open the volume panel.

## Usage Notes

- System-framework changes usually require a reboot to apply reliably.
- The volume-sidebar entry depends on `com.android.systemui`. Restart System UI if the entry does
  not appear after enabling the module.
- Apps that should be pinned to a specific output device must also be in the module scope.
- Volume-only rules are hosted by `android` (`system_server`). You do not need to scope every media
  app just to change volume.
- After a pinned device disconnects, the UI shows that output is following the system and keeps the
  original target.
- The first overlay open will ask for overlay permission.
- This module does not replace system media volume. It overlays volume and route rules onto each
  app's own players.

## Use Cases

- Play multiple apps at once, each with its own volume.
- Pin one app to Bluetooth or USB while others stay on the built-in speaker.
- Open the app volume panel quickly from the HyperOS volume sidebar.
- Adjust currently playing apps from a translucent panel above the desktop or another app.

## Build From Source

The project uses Kotlin, Jetpack Compose, Android Gradle Plugin, Gropify, and Gradle Wrapper.

- JDK 17.
- Android SDK / Build Tools 37.
- Compile SDK 37, min SDK 36, target SDK 37.

Common commands:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

Build outputs are generated under `app/build/outputs/`. Version name, git hash, branch, and build
channel are written from Gropify / git metadata.

## Help

- Report issues: [Issues](https://github.com/killerprojecte/SoundMan/issues)
- View releases: [Releases](https://github.com/killerprojecte/SoundMan/releases)
- Repository: [killerprojecte/SoundMan](https://github.com/killerprojecte/SoundMan)

## Disclaimer

- This module modifies system audio and System UI behavior. Evaluate the risk yourself.
- Compatibility can vary across system versions, firmware builds, and Xposed environments.
- Updates to the system framework or System UI may require Hook adaptation.
- You are responsible for any functional issues, audio-routing issues, or device risks caused by
  using this module.

## License

See [LICENSE](./LICENSE).
