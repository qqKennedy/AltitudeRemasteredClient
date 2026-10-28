# Altitude REMASTERED

HD sprites, sharp text, smoother windowed play and freeze-free screenshots for [Altitude](https://store.steampowered.com/app/41300/Altitude/) 1.1.9.
No gameplay changes; works on all servers.

![Stock (left) and HD (right)](docs/images/hd-sprites-planes.jpg)

## Install

Download the latest release from [Releases](../../releases) and follow the one line there.

## Options

Type `/opt` in the game console. All on by default:

| Option | What it does |
|---|---|
| `hdsprites` | HD sprites |
| `sharpfonts` | Sharp HUD text |
| `window` | Resizable window (or `borderless`, with `monitor`) |
| `scorehud` | Clearer ball-mode score |
| `fps` | Frame rate follows your monitor |
| `gamma` | Brightness slider works in a window |

Off by default: `framepacing` (steadier frames, more CPU), `perfhud` (frame-time overlay), `cursorlock play` (keep
the cursor in the game window while flying).

## Problems

Try `/opt hdsprites off`, then [report it](../../issues/new/choose) with the build ID from `/opt`.

## Developers

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) and [CONTRIBUTING.md](CONTRIBUTING.md). Code is GPL-3.0
([LICENSE](LICENSE)); the game's own classes, art and fonts are not covered.
