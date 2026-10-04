# CloudStream Extend

**An unofficial fork of [CloudStream](https://github.com/recloudstream/cloudstream) with built-in debrid support (Torrin, TorBox & Real-Debrid).**

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
+ **Plugins live in their own repository:** [**cloudstream-extend-plugins**](https://github.com/sandeep1027/cloudstream-extend-plugins) — the Torrin, HiAnime, AniKoto, AnimeCube, HDHub4u, BollyFlix and YTS extensions are maintained and released separately so they can be updated independently of the app. Install them from Settings → Extensions → Add repository; see that repo for the URLs and for how to build them.
+ **User-configurable TMDB API key:** Settings → Player → Metadata — supply your own free TMDB key (get one at [themoviedb.org](https://www.themoviedb.org/settings/api)). The plugins require this key to fetch metadata (plot, posters, trending rows); without it, metadata enrichment is gracefully skipped.
+ **User-configurable TMDB region and language:** Settings → Player → Metadata — choose your region (e.g., India, United States) and language (e.g., Hindi, English) to get localized content, trending movies/shows, and metadata in your preferred language.
+ **Download manager:** download content for offline viewing with configurable parallel downloads, Wi-Fi-only mode, quality preference, and auto-delete after watching. Settings → Player → Downloads.
+ **Enhanced subtitle support:** auto-download subtitles from OpenSubtitles for downloaded content, with configurable language, size, color, outline, font, and sync offset. Settings → Player → Subtitles.

### Install the plugins

The extensions are **not part of this repository** — they live in
[**cloudstream-extend-plugins**](https://github.com/sandeep1027/cloudstream-extend-plugins)
and are installed from there, so a plugin can be updated without touching the
app. Settings → Extensions → Add repository, then paste the URL for the plugin you
want (one repository per plugin, so you only install what you use):

| Plugin | Repository URL | Needs |
|---|---|---|
| Torrin | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/torrin/repo/repository.json` | debrid + TMDB key |
| Torrin MDBList | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/torrin-mdblist/repo/repository.json` | debrid + TMDB + MDBList key |
| Torrin Trakt | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/torrin-trakt/repo/repository.json` | debrid + TMDB + Trakt id |
| HiAnime | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/hianime/repo/repository.json` | nothing |
| AniKoto | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/anikoto/repo/repository.json` | nothing |
| AnimeCube | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/animecube/repo/repository.json` | nothing |
| HDHub4u | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/hdhub4u/repo/repository.json` | nothing |
| BollyFlix | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/bollyflix/repo/repository.json` | a debrid account |
| YTS | `https://raw.githubusercontent.com/sandeep1027/cloudstream-extend-plugins/main/plugins/yts/repo/repository.json` | a debrid account |

HiAnime, AniKoto, AnimeCube and HDHub4u stream directly and need no key. BollyFlix
and YTS are catalogue/torrent sources — neither site serves video, so every link
is a magnet resolved through your **Torrin / TorBox / Real-Debrid** account
(Settings → Player → Debrid), which you need to enable for them to play. The
Torrin plugins additionally want a free [TMDB API key](https://www.themoviedb.org/settings/api)
in Settings → Player → Metadata.

**One extra step:** the home screen only shows rows for ONE provider at a time. Tap the provider chip at the bottom of Home (it shows "None" by default) and pick the extension (e.g. "Torrin MDBList") — its rows then appear on Home.

The home screen also filters providers by content type, and only **Movies** and **TV Series** are selected by default. HiAnime, AniKoto and AnimeCube are anime-only, so enable the **Anime** chip at the top of Home (or pin the provider from the same chip dialog) to see their rows.

### Working on the plugins

Plugin source, the built `.cs3` packages and per-plugin build scripts are in
[**cloudstream-extend-plugins**](https://github.com/sandeep1027/cloudstream-extend-plugins),
which builds against the CloudStream library module pulled in as a submodule.
Its README covers building a `.cs3`, publishing a release (binary + sha256 go
together — the app verifies every download) and testing on a device via
`adb push … /sdcard/Cloudstream3/plugins/`.

### APK

Prebuilt APKs are available on the [Releases page](https://github.com/sandeep1027/cloudstream-extend/releases).

## Credits

+ Original app: [recloudstream/cloudstream](https://github.com/recloudstream/cloudstream) (GPL-3.0) — all credit for the base application goes to the CloudStream team and its contributors.
+ Fork & debrid integrations: `sandeep1027` (this repository).
+ HiAnime / AniKoto / AnimeCube / HDHub4u extensions: ported to the CloudStream plugin API from the community provider scrapers by **Spyou**; the JavaScript originals are MIT licensed. Maintained in [cloudstream-extend-plugins](https://github.com/sandeep1027/cloudstream-extend-plugins).
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
