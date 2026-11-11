# Changelog

Only the current version is kept here: each `vX.Y.Z` tag publishes the TV and Android APKs, and this section becomes the release notes. Older notes are on the [Releases page](https://github.com/fmhyplay/android/releases).

## 2.19.1

### ✨ New
- **United Kingdom, United States, Canada, Australia, New Zealand and Ireland** are in Settings, Your country (their rows are English-language titles).

### 🐞 Fixed
- **Servers and Sub / Dub dropdowns were skipped** on sites whose choices are plain words (Sub, Dub, Vidstream): a faulty check took them for numbers, so the player's Servers and Sub / Dub buttons had nothing to show there.
- **A stream whose address carries the page's address** (`master.m3u8?ref=https://site…`) was not recognised, so it never went to the player.
- **A show with "master" in its name** (MasterChef, Masters of the Air) could open in the wrong quality: every quality playlist under that folder was taken for the main one.
- **Titles not written in Latin letters** (Hindi, Korean, Japanese): opening one on a site without a direct link pressed a random link instead of searching for it.
- **A page could go blank** in its first half minute: a pop-up closer mistook the whole page for a pop-up on sites that mark the page while one is open.
- **WARP would not connect on a fresh install or after clearing data** ("Cloudflare is limiting new WARP sign-ups", HTTP 429), on mobile data and on Wi-Fi: since 2.18.0 the sign-up went out in a form Cloudflare refuses. It uses HTTP/2 again, and one connection at a time instead of one per address at once.
