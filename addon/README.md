# Pinterest Ad-Free v1.1 download add-on

GPL-3.0. It extends the [HushPinterest](https://github.com/SysAdminDoc/HushPinterest) v0.0.13 extension
(same Java package, `app.hushpinterest.extension.pinterest.actions`) and is added to the patched
Pinterest 14.41.0 APK as an extra dex file.

## Files

- `src/PxMedia.java`: finds every piece of media a pin carries (best MP4 / HLS playlist, original
  image, every idea-pin page, every carousel slot), with a fallback to the pin's public web page when
  the app's own pin model doesn't carry the media.
- `src/PxNet.java`: probing, plain downloads, and the HLS reader (master playlist, best variant,
  separate audio group, single-file CMAF byte ranges or segment lists).
- `src/PxSave.java`: saves to MediaStore (Pictures/Pinterest, Movies/Pinterest) with a progress
  notification, remuxes HLS renditions into one MP4 with `MediaExtractor` + `MediaMuxer` (no
  re-encoding), and copies an image or video to the clipboard.
- `src/PxBar.java`: replaces the react (heart) and comment buttons in the pin close-up's primary
  action bar with Download and Copy.

## Hooks (smali, in the patched APK)

| Where | What |
|---|---|
| `ec1/u0` constructor (pin close-up primary action bar), before `return-void` | `PxBar.attach(bar, reactView, commentsView)` |
| HushPinterest `PinDownloads.start(Object, Context)` | `PxSave.download(pin, context)` first; HushPinterest's own path if it returns false |
| HushPinterest `PinDownloads.start(Object, Context, Consumer)` (batch) | `PxSave.downloadBatch(...)` for HLS / multi-item pins |
| HushPinterest `PinDownloads.request(Source, String, String)` | `PxSave.queueSource(source)`: an HLS playlist becomes the MP4 beside it |
| HushPinterest `PinMedia.resolve(Object)` | renamed `resolveHush`; the new `resolve` passes its answer through `PxMedia.fixResolution` so HLS / idea-pin videos get a Download row |
| HushPinterest `Settings` | `download_pins`, `long_press_download`, `download_board`, `copy_image`, `share_image` default to on |
| `AndroidManifest.xml` | versionCode 14418020 → 14418021 |

## WhatsApp channel card (v14.41.0-channel)

- `src/banner/ChannelBanner.java` (package `paradox.banner`): the launch card inviting users to the Paradox WhatsApp channel (Join channel / Later / Don't show again; once per app start; remembered in its own SharedPreferences). Added to the APK as its own extra dex.
- Hook (smali): `com.pinterest.activityLibrary.activity.task.activity.MainActivity.onCreate`, right after `super.onCreate(...)`: `ChannelBanner.show(this)`.

## Update check (v14.41.0-update)

- `src/banner/UpdateCheck.java` (package `paradox.banner`): on launch, at most every 12 hours, reads `https://api.github.com/repos/chinmays-tanx09/pinterest-adfree/releases`, picks the newest stable release with an `.apk` asset and a `versionCode: N` line, and if N is higher than the installed versionCode shows an "Update available" card (Download opens the APK link in the browser / Later / Skip this version). Silent on any error, no telemetry, no new permissions. Started from `ChannelBanner.show`, so the existing hook covers it; the two cards queue one after the other.
- `src/banner/ChannelBanner.java`: updated to start the update check and to queue its card with the update card. Same extra dex as before.
- `AndroidManifest.xml`: versionCode 14418022 → 14418023.
