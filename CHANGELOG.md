# Changelog

## Next

- Frame rate follows the monitor's refresh rate, also in a window (stock: 60 fps) (`fps`).
- Screenshots no longer freeze the game.
- Borderless window on a chosen monitor (`window borderless`, `monitor`).
- Brightness slider works in a window (`gamma`).
- `stockfonts` is now `sharpfonts` (on).
- Cursor kept in the game window while flying, released in menus, console, chat, when dead or alt-tabbed
  (`cursorlock play`, off by default).

## v1.0.0-beta

First public beta, for Altitude 1.1.9.

- **HD sprites** (`hdsprites`, on): 225 world textures (planes in every team colour, skins, bases, turrets,
  powerups, projectiles, effects, map pieces) upscaled with waifu2x and corrected so they shrink back to the stock
  pixels exactly. Shipped as a 3x pack; the client uses stock, 2x or 3x depending on the window. Only the official
  pack is accepted.
- **Sharp HUD text** (`sharpfonts`): Verdana, Arial, Courier New and Blambot Casual drawn at the screen's
  resolution with the stock layout.
- **Resizable window** (`window resizable`).
- **Ball-mode score HUD** (`scorehud`): a backing behind the score and the announced sudden-death target, with an
  estimate after joining mid sudden death.
- **Faster map loading**: map image packs decoded on worker threads, game art precached in the menu.
- **Frame pacing** (`framepacing`, off, please test): steadier frame times for some CPU.
- **Perf overlay and log** (`perfhud`, `perflog`): per-second frame-time statistics, GC pauses, ticks, ping and heap.
- `/opt` console command for all of the above, with the build ID on its first line.
- Installer script with backup and uninstall (`remastered.ps1` in the bundle).
