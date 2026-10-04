# CloudStream Extend

**An unofficial fork of [CloudStream](https://github.com/recloudstream/cloudstream) with built-in debrid support (Torrin, TorBox & Real-Debrid) and a bundled Indian-content extension.**

> ⚠️ **Legal notice:** This project is a **fork** of [recloudstream/cloudstream](https://github.com/recloudstream/cloudstream), which is licensed under the **GNU General Public License v3.0 (GPL-3.0)**. The GPL license file is preserved in this repository (`LICENSE`). All original copyright belongs to the CloudStream authors and contributors. All modifications made in this fork are released under the same GPL-3.0 license.
>
> ⚠️ By default, the app doesn't provide any video sources; you have to install extensions to add functionality. This project does not host, stream, or provide any copyrighted media — playback is resolved through the user's own Torrin/TorBox/Real-Debrid accounts, and content sources come from community extensions.
>
> ⚠️ **First-launch disclaimer:** On first launch the app presents a legal disclaimer that must be accepted before use. It explains that the app does not host content, that users are responsible for complying with their local laws, and that all content comes from third-party extensions. Declining exits the app.

[![Discord](https://invidget.switchblade.xyz/5Hus6fM)](https://discord.gg/5Hus6fM)

## What this fork adds

+ **Torrin debrid integration (app core):** magnet links from any extension are resolved through your [Torrin](https://torrin.app) account into direct, signed HTTPS streams (no local torrent engine needed). Settings → Player → Debrid.
+ **TorBox debrid integration (app core):** same flow for [TorBox](https://torbox.app). Falls back Torrin → TorBox → local torrent automatically.
+ **Real-Debrid debrid integration (app core):** same flow for [Real-Debrid](https://real-debrid.com). Full support for magnet submission, file selection, and link unrestricting.
+ **Parallel debrid resolution:** when multiple debrid services are enabled, they run in parallel and use the first successful result for faster stream resolution.
+ **Smart caching:** in-memory cache with 2-hour TTL avoids repeated API calls for the same content, significantly improving performance.
+ **Retry logic with exponential backoff:** automatic retry (3 attempts) for transient API failures with exponential backoff (1s, 2s, 4s) for reliable operation.
+ **Debug logging:** centralized logging system with preference toggle (Settings → Player → Debrid → Debug logging) to monitor debrid operations.
+ **Test connection buttons:** verify your API credentials for Torrin, TorBox, and Real-Debrid directly from settings before use.
+ **Per-link debrid choice:** extensions can tag a magnet link with a debrid preference — links appear as "• Torrin • …" and "• TorBox • …" variants, the app tries the chosen debrid first and falls back to the other, so you pick which debrid plays each title.
+ **Torrin extension (this repo, `plugins/torrin/`):** curated dashboard + "Latest on Netflix / Hotstar / ZEE5 / SonyLIV" rows for Indian content (TMDB, `origin_country=IN`), with playback routed through the debrid layer. Requires your TMDB API key for metadata.
+ **Torrin MDBList extension (`plugins/torrin-mdblist/`):** "Latest Movies" / "Latest Shows" rows from the [MDBList](https://mdblist.com) catalog (free API key, Settings → Player → Metadata) plus trending rows, with Torrin/TorBox debrid playback. Requires your TMDB API key for metadata.
+ **Torrin Trakt extension (`plugins/torrin-trakt/`):** "Latest Movies" / "Latest Episodes" rows from the public [Trakt](https://trakt.tv) calendar (free client id, Settings → Player → Metadata) plus trending rows, with Torrin/TorBox debrid playback. Requires your TMDB API key for metadata.
+ **Anime extension (`plugins/anime/`):** full [AniList](https://anilist.co) integration with GraphQL API for anime discovery and playback through debrid services.
+ **HiAnime extension (`plugins/hianime/`):** [hianime.at](https://hianime.at) anime source — home rows (Spotlight / Trending / Latest Episode / New On HiAnime / Top Upcoming), search, detail with synopsis and episodes, and a Sub/Dub toggle per episode. Streams are resolved from the site's server list through VidPlay/MegaPlay `getSources` and Zoko's obfuscated player blob into m3u8/MP4 with subtitles.
+ **AniKoto extension (`plugins/anikoto/`):** [anikototv.to](https://anikototv.to) anime source — home rows, search, detail and episodes, sub/dub, and streams resolved through the site's two-step server chain, including MegaPlay's AES-encrypted source payloads.
+ **AnimeCube extension (`plugins/animecube/`):** [animecube.live](https://animecube.live) anime source — listings and episodes read from the site's Next.js data payload, streams from its sources endpoint with Dailymotion and Rumble playlists expanded into direct HLS/MP4.
+ **HDHub4u extension (`plugins/hdhub4u/`):** HDHub4u movies and series (Hindi/Hollywood) — home rows, search, detail with episodes, and stream extraction through the site's shortener/link-bypass hops.
+ **BollyFlix extension (`plugins/bollyflix/`):** the [BollyFlix](https://new.bollyflix.vote) catalogue (Bollywood, Hollywood, dual audio, Hindi-dubbed, Korean) — category rows, search and detail read from the site's WordPress REST API, including the per-title quality table (`2160p HEVC • 12GB`). The site itself only publishes **download** links, so this plugin is metadata-only: playback is resolved from the torrent layer and streamed through your **Torrin / TorBox / Real-Debrid** account (Settings → Player → Debrid), exactly like the Torrin extension.
+ **YTS extension (`plugins/yts/`):** [YTS](https://en.yts.lu) movies and TV shows — browse and search come from the site's TMDB-backed API, with home rows per streaming service (**Netflix, Prime Video, Disney+, Max, Hulu**, plus the TV equivalents), freshness windows (**This Week**, **Today**), regional rows (**Indian** movies and shows), genres and the usual Popular / Top Rated shelves. Every link is a magnet labelled with quality, size, seeders and tracker (`2160p • 20.32 GB • 234 seeders`), so playback needs a debrid account. Sites in this family move domains often, which is why it is a standalone plugin that can be pointed at a new mirror without touching the others.
+ **User-configurable TMDB API key:** Settings → Player → Metadata — supply your own free TMDB key (get one at [themoviedb.org](https://www.themoviedb.org/settings/api)). The plugins require this key to fetch metadata (plot, posters, trending rows); without it, metadata enrichment is gracefully skipped.
+ **User-configurable TMDB region and language:** Settings → Player → Metadata — choose your region (e.g., India, United States) and language (e.g., Hindi, English) to get localized content, trending movies/shows, and metadata in your preferred language.
+ **Download manager:** download content for offline viewing with configurable parallel downloads, Wi-Fi-only mode, quality preference, and auto-delete after watching. Settings → Player → Downloads.
+ **Enhanced subtitle support:** auto-download subtitles from OpenSubtitles for downloaded content, with configurable language, size, color, outline, font, and sync offset. Settings → Player → Subtitles.

### Install the plugins

Settings → Extensions → Add repository — pick the repo for the extension you want (one repo per extension, so you can switch between them):

**Torrin** (Indian platform rows):

```
https://raw.githubusercontent.com/sandeep1027/cloudstream-extend/main/plugins/torrin/repo/repository.json
```

**Torrin MDBList** (MDBList latest releases — needs your free MDBList API key in Settings → Player → Metadata):

```
https://raw.githubusercontent.com/sandeep1027/cloudstream-extend/main/plugins/torrin-mdblist/repo/repository.json
```

**Torrin Trakt** (Trakt calendar latest releases — needs your free Trakt client id in Settings → Player → Metadata):

```
https://raw.githubusercontent.com/sandeep1027/cloudstream-extend/main/plugins/torrin-trakt/repo/repository.json
```

**HiAnime / AniKoto / AnimeCube / HDHub4u** (direct sources — no debrid, no API key). These ship from the `providers` branch:

```
https://raw.githubusercontent.com/sandeep1027/cloudstream-extend/providers/plugins/hianime/repo/repository.json
https://raw.githubusercontent.com/sandeep1027/cloudstream-extend/providers/plugins/anikoto/repo/repository.json
https://raw.githubusercontent.com/sandeep1027/cloudstream-extend/providers/plugins/animecube/repo/repository.json
https://raw.githubusercontent.com/sandeep1027/cloudstream-extend/providers/plugins/hdhub4u/repo/repository.json
```

**BollyFlix** (catalogue only — playback goes through your debrid account, needs no API key):

```
https://raw.githubusercontent.com/sandeep1027/cloudstream-extend/providers/plugins/bollyflix/repo/repository.json
```

**YTS** (torrents — playback goes through your debrid account, needs no API key):

```
https://raw.githubusercontent.com/sandeep1027/cloudstream-extend/providers/plugins/yts/repo/repository.json
```

Add each repo you want, then install the extension from it. They are independent — a source going down only affects its own plugin.

Then enable your debrid account(s) in Settings → Player → Debrid. With both Torrin and TorBox enabled, every quality tier / episode is offered once per debrid — tap the one you want. The three Torrin plugins require your free [TMDB API key](https://www.themoviedb.org/settings/api) in Settings → Player → Metadata for metadata (plot, posters, trending rows); the four direct sources need no key and no debrid. **BollyFlix and YTS need a debrid account** (Torrin, TorBox or Real-Debrid) for playback, since neither site serves video itself.

**One extra step:** the home screen only shows rows for ONE provider at a time. Tap the provider chip at the bottom of Home (it shows "None" by default) and pick the extension (e.g. "Torrin MDBList") — its rows then appear on Home.

The home screen also filters providers by content type, and only **Movies** and **TV Series** are selected by default. HiAnime, AniKoto and AnimeCube are anime-only, so enable the **Anime** chip at the top of Home (or pin the provider from the same chip dialog) to see their rows.

### Build a source plugin locally

Each plugin module builds its own `.cs3` — the zip of `classes*.dex` + `manifest.json` that the app loads:

+ Linux/macOS: `plugins/<module>/build_cs3.sh`
+ Windows: `plugins/<module>/build_cs3.bat` (reads the SDK from `local.properties`, falls back to `ANDROID_HOME`)

The script prints the `fileSize` and sha256 `fileHash` to paste into that module's `repo/plugins.json`. Test without publishing by copying the built `.cs3` to the device and restarting the app — every `.cs3`/`.zip` in `<external storage>/Cloudstream3/plugins/` is loaded at startup (`PluginManager.loadAllLocalPlugins`):

```
adb push plugins/hianime/build/cs3/HiAnime.cs3 /sdcard/Cloudstream3/plugins/
adb shell am start -a android.intent.action.VIEW -d "cloudstreamapp:"   # hot reload, no restart
adb logcat | findstr /i PluginManager
```

Note for playback: many of these hosts answer `403` unless the request carries the embed's `Referer`, and the player's default HTTP stack (Cronet) drops it. Each of these providers therefore returns an OkHttp `Interceptor` from `getVideoInterceptor`, which moves playback onto the data source that does send it.

### APK

Prebuilt APKs are available on the [Releases page](https://github.com/sandeep1027/cloudstream-extend/releases).

## Credits

+ Original app: [recloudstream/cloudstream](https://github.com/recloudstream/cloudstream) (GPL-3.0) — all credit for the base application goes to the CloudStream team and its contributors.
+ Fork & debrid integrations: `sandeep1027` (this repository).
+ HiAnime / AniKoto / AnimeCube / HDHub4u extensions: ported to the CloudStream plugin API from the community provider scrapers by **Spyou**; the JavaScript originals are MIT licensed.
+ Torrin API: [torrin.app](https://torrin.app) · TorBox API: [torbox.app](https://torbox.app) · Real-Debrid API: [real-debrid.com](https://real-debrid.com) · TMDB data: [themoviedb.org](https://www.themoviedb.org/) (used per [their API terms](https://www.themoviedb.org/documentation/rules)).

## License

GPL-3.0 — see [LICENSE](LICENSE). This fork is a derivative work of CloudStream and must be distributed under the same terms: keep this license, keep attribution, and publish source for any modifications (this repository is the source).

---

## Table of Contents (upstream)
+ [About Us:](#about_us)
+ [Installation Steps:](#install_rules)
+ [Contributing:](#contributing)
+ [Issues:](#issues)
  + [Bugs Reports:](#bug_report)
  + [Enhancement:](#enhancment)
+ [Extension Development:](#extensions)
+ [Language Support:](#languages)
+ [Further Sources](#contact_and_sources)


<a id="about_us"></a>

## About us: 

**CloudStream is a media center that prioritizes and emphasizes complete freedom and flexibility for users and developers.** 

CloudStream is an extension-based multimedia player with tracking support. There are extensions to view videos from: 

+ [Librevox (audio-books)](https://librivox.org/) 
+ [Youtube](https://www.youtube.com/)
+ [Twitch](https://www.twitch.tv/)
+ [iptv-org (A collection of publicly available IPTV (Internet Protocol television) channels from all over the world.)](https://github.com/iptv-org/iptv) 
+ [nginx](https://nginx.org/)
+ And more... 


**Please don't create illegal extensions or use any that host any copyrighted media.** For more details about our stance on the DMCA and EUCD, you can read about it on our organization: [reCloudStream](https://github.com/recloudstream)

#### Important Copyright Note: 

Our documentation is unmaintained and open to contributions; therefore, apps and sources, extensions in recommended sources, and recommended apps are not officially moderated or endorsed by CloudStream; if you or another copyright owner identify an extension that breaches your copyright, please let us know. 


#### Features:
+ **AdFree**, No ads whatsoever
+ No tracking/analytics
+ Bookmarks
+ Phone and TV support
+ Chromecast
+ Extension system for personal customization


<a id="install_rules"></a>

## Installation: 

Our documentation provides the steps to install and configure CloudStream for your streaming needs.

[Getting Started With CloudStream:](https://recloudstream.github.io/csdocs/)

<a id="contributing"></a>

## Contributing:
We **happily** accept any contributions to our project. To find out where you can start contributing towards our project, please look [at our issues tab](/cloudstream/issues)



<a id="issues"></a> 
 
### Issues: 
While we **actively** accept issues and pull requests, we do require you fill out an [template](https://github.com/recloudstream/cloudstream/issues/new/choose) for issues. These include the following:

<a id="bug_report"></a>

- [Bug Report Template: ](https://github.com/recloudstream/cloudstream/issues/new?assignees=&labels=bug&projects=&template=application-bug.yml)
  - For bug reports, we want as much info as possible, including your downloaded version of CloudeStream, device and updated version (if possible, current API),
    expected behavior of the program, and the actual behavior that the program did, most importantly we require clear, reproducible steps of the bug. If your bug can't be       reproduced, it is unlikely we'll work on your issue.
    
<a id="enhancment"></a>
  
- [Feature Request Template: ](https://github.com/recloudstream/cloudstream/issues/new?assignees=&labels=enhancement&projects=&template=feature-request.yml)
  - Before adding a feature request, please check to see if a feature request already has been requested.  


### Extensions:
 
**Further details on creating extensions for CloudStream are found in our documentation.**

[Guide: For Extension Developers](https://recloudstream.github.io/csdocs/devs/gettingstarted/) 

<a id="contact_and_sources"></a>

## Further Sources: 

As well as providing clear install steps, our [website](https://dweb.link/ipns/cloudstream.on.fleek.co/) includes a wide variety of other tools, such as: 
- [Troubleshooting](https://recloudstream.github.io/csdocs/troubleshooting/)
- [Further CloudStream Repositories](https://recloudstream.github.io/csdocs/repositories/) 
- Set-Up for other devices, such as:
  - [Android TV](https://recloudstream.github.io/csdocs/other-devices/tv/)
  - [Windows](https://recloudstream.github.io/csdocs/other-devices/windows/)
  - [Linux](https://recloudstream.github.io/csdocs/other-devices/linux/)
- And more...

<a id="languages"> </a>  

### Supported languages:

Even if you can't contribute to the code or documentation, we always look for those who can contribute in translation and language support. Your contribution is exceptionally appreciated; you can check our translation from the figure below. 

<a href="https://hosted.weblate.org/engage/cloudstream/">
  <img src="https://hosted.weblate.org/widgets/cloudstream/-/app/multi-auto.svg" alt="Translation status" />
</a>
