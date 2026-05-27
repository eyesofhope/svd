# Super Video Downloader

> ⚠️ **Project status: under active development.** Features are being redesigned, APIs may change, and you may encounter rough edges. Not production ready yet.

An Android app for downloading video (and optionally audio) from a wide range of websites, with a built-in browser, download manager, and offline player.

## Disclaimer

This project is built for research and educational purposes. The developer is not responsible for how end users choose to use it.

## Features (current focus)

- **Video-first download flow** with a redesigned bottom-sheet popup that shows thumbnail, title, file size and per-quality options in a single card.
- **Wide site support** via a yt-dlp-style backend (works on YouTube, Facebook, Instagram, Twitter, Dailymotion, Vimeo and many others).
- **HLS / DASH / MP4 detection** including sibling media playlists merged into one entry per video.
- **Built-in browser** with history, bookmarks and cookie support.
- **Download manager** running in the background.
- **Offline player** for downloaded files.
- **Networking extras**: proxy chaining (HTTP / SOCKS) and encrypted DNS (DoH / DoT).

Audio-only downloading is intentionally secondary while the video pipeline is being stabilised.

## Tech stack

- Kotlin, MVVM + Repository pattern
- Android Views with DataBinding
- Dagger 2, Coroutines, RxJava
- Room, OkHttp, libv2ray

## Build

From the repository root:

```bash
# macOS / Linux
./gradlew :app:assembleDebug
```

```powershell
# Windows
.\gradlew.bat :app:assembleDebug
```

If `go` isn't on PATH, point Gradle at it:

```bash
./gradlew -PGO_EXECUTABLE=/path/to/go :app:vendorGoDependencies
```

Clean build artefacts with `./gradlew clean`.

## Credits

Inspired by [cuongpm/youtube-dl-android](https://github.com/cuongpm/youtube-dl-android). Thanks to [@cuongpm](https://github.com/cuongpm), [@yausername](https://github.com/yausername) and [@JunkFood02](https://github.com/JunkFood02).

## License

See [LICENSE](./LICENSE).
