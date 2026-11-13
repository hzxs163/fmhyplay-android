# Changelog

Only the current version is kept here: each `vX.Y.Z` tag publishes the TV and Android APKs, and this section becomes the release notes. Older notes are on the [Releases page](https://github.com/fmhyplay/android/releases).

## 2.20.1

### ✨ New
- **The download question** shows the **full path** of the folder (`/storage/emulated/0/Download/FMHYPlay/`), and a pencil next to the file name and the folder lets you change either one before it starts. The dialog is simpler: the file name and the folder, nothing else.

### 🐞 Fixed
- **Downloads of streams that keep the sound in a separate playlist** (dual audio, other languages) had no sound. The sound is now downloaded too and joined with the picture into one `.mp4` (a moment at the end); pausing and resuming work as before.
- **Up closes the strip** of channels, episodes or more to watch that Down opens at the bottom of the player. It used to close only with Back. The remote goes back to the button that opened it.
