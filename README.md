<p align="center"><img src="images/icon.png" width="128" alt="Pinterest icon"></p>

<h1 align="center">Pinterest Ad-Free</h1>

<p align="center">The official Pinterest app (14.41.0), patched to remove ads, with a one-tap Download button on every pin and privacy switches.<br><b>Unofficial. Not affiliated with, endorsed by or supported by Pinterest, Inc.</b></p>

<p align="center"><a href="https://github.com/chinmays-tanx09/pinterest-adfree/releases/latest"><b>Download the latest APK</b></a></p>

<p align="center"><img src="images/screenshot1.jpg" width="240" alt="Home feed"> <img src="images/screenshot2.jpg" width="240" alt="Home feed"> <img src="images/screenshot3.jpg" width="240" alt="Profile"></p>

## What it is

This is Pinterest's own Android app, version 14.41.0, patched with the open-source [HushPinterest](https://github.com/SysAdminDoc/HushPinterest) patches (v0.0.13, GPL-3.0) using the [Morphe](https://morphe.software) patcher. On top of those patches, v1.1 adds a small open-source download add-on (source in [`addon/`](addon), GPL-3.0 like the patches it extends). All credit for the HushPinterest patches goes to their authors.

## Features

New in v1.1:
- **Download button on every pin**: on a pin's page the heart (react) button is replaced by Download. One tap saves the pin straight to your gallery (photos in Pictures/Pinterest, videos in Movies/Pinterest) with a progress notification.
- **Copy button**: the comment button is replaced by Copy, which puts the pin's actual image or video on the clipboard (paste it into WhatsApp, Telegram and other apps that take pasted media). A copied video is also saved to the gallery.
- **Best quality**: original-resolution images (GIFs stay GIFs), every page of an idea pin, every slide of a carousel, and videos as the best MP4. Stream-only (HLS) videos are fetched as the MP4 Pinterest keeps alongside them, or downloaded and remuxed into an MP4 on the phone.
- Download from the ⋯ menu, the long-press menu, several pins at once or a whole board, **on by default**.

Also on by default:
- **No ads**: promoted pins are removed from the feed.
- **Privacy**: analytics disabled, advertising ID hidden, ad-tracking permissions removed, tracking stripped from links.
- **Push notifications** fixed for the patched app.

Available as switches in **Settings → HushPinterest settings** (off until you turn them on):
- Hide AI-labeled pins, shopping/product pins, comments, survey prompts, topic suggestions and search history.
- Original-quality images, AMOLED black theme, show posted date.
- Open links in your own browser, use the system share sheet, no screenshot share menu.
- Keep the Home feed, hide header/navigation/close-up buttons, quieter email reminders, no update nag.

## Install

Already have v14.41.0 from here? Just install the new APK over it; your login stays.

1. **Uninstall the official Pinterest app first.** This build keeps Pinterest's package name (`com.pinterest`) but is signed with a different key, so Android won't install it over the Play Store version, and it can't sit next to it.
2. Download `Pinterest_AdFree_v14.41.0-dl.apk` from [Releases](https://github.com/chinmays-tanx09/pinterest-adfree/releases/latest) and install it.
3. Sign in with your Pinterest e-mail and password.

Needs Android 10 or newer. The APK is universal (arm64-v8a, armeabi-v7a, x86, x86_64).

## Known limits

- **Not tested on a real phone yet.** The patching finished with every patch applied, and the download add-on's media picking and HLS download were tested on a computer against real pins, but nobody has run this build on a device. Please report problems.
- The heart and comment buttons on a pin's page are now Download and Copy, so reacting from that row isn't possible; comments are still shown under the pin.
- Copy puts the first item of a carousel or idea pin on the clipboard.
- **"Continue with Google" may not work**: the Google sign-in support patches were not included. Use e-mail and password.
- Don't update it from the Play Store: that replaces it with the official app (with ads). Updates of this build come from this repository.

## Verify your download

Package `com.pinterest`, version 14.41.0 (versionCode 14418021).
Signing certificate SHA-256: `650d96882604cff599411cc260545d7cfcde1edf2681674db16ee7ee8088687a`
APK SHA-256 (`Pinterest_AdFree_v14.41.0-dl.apk`): `343a56935bc82323d453f01c2297140288eb7bc9f4b7ed17188f525ba63d92d5`

## License and credits

- Pinterest and the Pinterest logo are trademarks of Pinterest, Inc. The Pinterest app is Pinterest's software.
- Patches: [HushPinterest](https://github.com/SysAdminDoc/HushPinterest) by SysAdminDoc, GPL-3.0. The source of the patches used here is the [v0.0.13 source](https://github.com/SysAdminDoc/HushPinterest/tree/v0.0.13). Patcher: [Morphe](https://github.com/MorpheApp).
- Download add-on: [`addon/`](addon), GPL-3.0.
- See [LICENSE](LICENSE).
