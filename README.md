# CloudStream Extend

**An unofficial fork of [CloudStream](https://github.com/recloudstream/cloudstream) with built-in debrid support (Torrin & TorBox) and a bundled Indian-content extension.**

> ⚠️ **Legal notice:** This project is a **fork** of [recloudstream/cloudstream](https://github.com/recloudstream/cloudstream), which is licensed under the **GNU General Public License v3.0 (GPL-3.0)**. The GPL license file is preserved in this repository (`LICENSE`). All original copyright belongs to the CloudStream authors and contributors. All modifications made in this fork are released under the same GPL-3.0 license.
>
> ⚠️ By default, the app doesn't provide any video sources; you have to install extensions to add functionality. This project does not host, stream, or provide any copyrighted media — playback is resolved through the user's own Torrin/TorBox accounts, and content sources come from community extensions.

[![Discord](https://invidget.switchblade.xyz/5Hus6fM)](https://discord.gg/5Hus6fM)

## What this fork adds

+ **Torrin debrid integration (app core):** magnet links from any extension are resolved through your [Torrin](https://torrin.app) account into direct, signed HTTPS streams (no local torrent engine needed). Settings → Player → Debrid.
+ **TorBox debrid integration (app core):** same flow for [TorBox](https://torbox.app). Falls back Torrin → TorBox → local torrent automatically.
+ **Per-link debrid choice:** extensions can tag a magnet link with a debrid preference — links appear as "• Torrin • …" and "• TorBox • …" variants, the app tries the chosen debrid first and falls back to the other, so you pick which debrid plays each title.
+ **Torrin extension (this repo, `plugins/torrin/`):** curated dashboard + "Latest on Netflix / Hotstar / ZEE5 / SonyLIV" rows for Indian content (TMDB, `origin_country=IN`), with playback routed through the debrid layer.
+ **Torrin MDBList extension (`plugins/torrin-mdblist/`):** "Latest Movies" / "Latest Shows" rows from the [MDBList](https://mdblist.com) catalog (free API key, Settings → Player → Metadata) plus trending rows, with Torrin/TorBox debrid playback.
+ **Torrin Trakt extension (`plugins/torrin-trakt/`):** "Latest Movies" / "Latest Episodes" rows from the public [Trakt](https://trakt.tv) calendar — no API keys — plus trending rows, with Torrin/TorBox debrid playback.
+ **Torrin TMDB extension (`plugins/torrin-tmdb/`):** "Latest Movies" / "Latest Shows" rows from [TMDB](https://www.themoviedb.org) Discover — both India-only and Global — plus trending rows. TMDB is used instead of MDBList here because its rate limits are per-second (not a small weekly unique-query cap), so the rows refresh daily without freezing. Per-debrid (Torrin/TorBox) playback links.
+ **User-configurable TMDB API key:** Settings → Player → Metadata — supply your own key or leave blank to use the built-in shared key.

### Install the plugins

Settings → Extensions → Add repository — pick the repo for the extension you want (one repo per extension, so you can switch between them):

**Torrin** (Indian platform rows):

```
https://raw.githubusercontent.com/sandeep1027/cloudstream-extend/torrin-support/plugins/torrin/repo/repository.json
```

**Torrin MDBList** (MDBList latest releases — needs your free MDBList API key in Settings → Player → Metadata):

```
https://raw.githubusercontent.com/sandeep1027/cloudstream-extend/mdblist-support/plugins/torrin-mdblist/repo/repository.json
```

**Torrin Trakt** (Trakt calendar latest releases — no keys needed):

```
https://raw.githubusercontent.com/sandeep1027/cloudstream-extend/trakt-support/plugins/torrin-trakt/repo/repository.json
```

**Torrin TMDB** (TMDB latest releases, India + Global — uses your TMDB key from Settings → Player → Metadata, or the built-in key; no MDBList quota):

```
https://raw.githubusercontent.com/sandeep1027/cloudstream-extend/tmdb-support/plugins/torrin-tmdb/repo/repository.json
```

Then install the extension from the repository and enable your debrid account(s) in Settings → Player → Debrid. With both Torrin and TorBox enabled, every quality tier / episode is offered once per debrid — tap the one you want.

**One extra step:** the home screen only shows rows for ONE provider at a time. Tap the provider chip at the bottom of Home (it shows "None" by default) and pick the extension (e.g. "Torrin MDBList") — its rows then appear on Home.

### APK

Prebuilt APKs are available on the [Releases page](https://github.com/sandeep1027/cloudstream-extend/releases).

## Credits

+ Original app: [recloudstream/cloudstream](https://github.com/recloudstream/cloudstream) (GPL-3.0) — all credit for the base application goes to the CloudStream team and its contributors.
+ Fork & debrid integrations: `sandeep1027` (this repository).
+ Torrin API: [torrin.app](https://torrin.app) · TorBox API: [torbox.app](https://torbox.app) · TMDB data: [themoviedb.org](https://www.themoviedb.org/) (used per [their API terms](https://www.themoviedb.org/documentation/rules)).

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
