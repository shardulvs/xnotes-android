<p align="center"><img src="docs/banner.png" height="96" alt="Sponka" /></p>

<p align="center">
  A handwriting-first notebook for Android, built for pen and stylus. A customized fork of xnotes.<br />
  Formerly n-notes, renamed n-notes -> sponka because the fork has grown far enough from xnotes to get its own name and icon. App icon by <a href="https://github.com/failure-404">@failure-404</a>.
</p>

<p align="center">
  <a href="https://github.com/NeDDy3z/n-notes/releases/latest"><img src="https://img.shields.io/github/v/release/NeDDy3z/n-notes?style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
</p>

---

## Why this fork

Xnotes is a great app, and I'm grateful that the developer puts so much work into it and shares it openly. However, in my day to day use I noticed a few missing features and some details that bothered me. Above all, I really wanted some kind of cloud sync, so I made this fork. I do my best to keep it in sync with the original repository, and I almost always prefer the original developer's changes over mine, since they are much better implemented.

[Long term support question](https://github.com/NeDDy3z/n-notes/issues/13)

## Additions to the original xnotes

sponka adds more then 50+ features on top of xnotes, while 10+ (2026.11.01) of them were later implemented in xnotes as well. See the full list in [ADDITIONS.md](ADDITIONS.md).

## Versioning

Releases are named `{xnotes version}-{sponka version}`, for example `0.9.7-0.48`: the upstream xnotes release the build is based on, then the fork revision, bumped on every published build.

## Install

| Channel | |
|---|---|
| [GitHub Releases](https://github.com/NeDDy3z/n-notes/releases/latest) | Signed APK |

### Build it yourself

Requires **JDK 17** (the project pins Java 17):

```bash
git clone https://github.com/NeDDy3z/n-notes.git
cd n-notes
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Reporting issues

When reporting bugs, issues or ideas, please split them into multiple smaller issues instead of one big one. That way they're easier for me to track and I won't miss any bug or requested feature.

## Disclaimer

I use LLMs during development mainly for research, code review and translations. Any and every change an LLM makes, I manually review/test before it goes into a release.

Translations are fully LLM-generated and get fixed over time based on user reports. If you spot a translation issue, please report it [here](https://github.com/NeDDy3z/n-notes/issues/new?title=Incorrect%20Translation&labels=translation&assignees=NeDDy3z&body=Language:%0ALocation:%0AIncorrect:%0AFix:).

## License

Released under the MIT License, the same as upstream. See [LICENSE](LICENSE).

sponka (formerly n-notes) is a fork of [xnotes](https://github.com/shardulvs/xnotes-android) by Shardul Vikram Singh.
