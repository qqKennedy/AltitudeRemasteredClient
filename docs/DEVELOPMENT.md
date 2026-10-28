# Development

How the client is built, tested and put together. For the rules on what a change may do, see
[CONTRIBUTING.md](../CONTRIBUTING.md).

## How the patch works

The client jar is the stock `Altitude-1.1.9.jar` with a few static calls inserted into stock methods, plus new classes
in `em.altitude.game.scene.player`. Every change belongs to a named fix in
[`build-tools/src/client/build/Hooks.java`](../build-tools/src/client/build/Hooks.java), and the build fails on any
difference from stock that is not listed there.

| Fix | Option (default) | Stock methods hooked |
|---|---|---|
| `/opt` command, option store, build ID | – | `AltitudeConsoleCommandManager.createCommands` (entry) |
| Faster image loading | `-Dclient.stockimages=true` turns it off | `ImagePack.readImageBytes`, `ImageManager.getPrecacheLoadImagesTask` (entry) |
| Frame pacing | `framepacing` (off) | `GLFrame.sleep` (entry, may return early) |
| Sharp HUD fonts | `sharpfonts` (on) | `Font.render(CharSequence,int,int,int,int)` (entry, may return early), `Font.reloadImage` (entry) |
| Resizable or borderless window | `window` (resizable), `monitor` (1) | `GLFrame.setDisplayMode` (entry, before return) |
| Frame-rate target | `fps` (auto) | `GLFrame.setDisplayMode` (before return) |
| Screenshots off the render thread | – | `SceneManager.takeScreenshot` (entry, may return early) |
| Brightness in the game image | `gamma` (image) | `GLFrame.setDisplayConfiguration` (entry, may return early), `AltitudeGame.render` (before return) |
| Score HUD (ball mode) | `scorehud` (on) | `PlayerScene.onChatEvent` (entry), `PlayerScene.render` (before return) |
| Perf HUD and log | `perfhud`, `perflog` (off); `/opt perf` | `PlayerScene.render` (before return) |
| HD sprites | `hdsprites` (on) | `ImagePack.loadTextureFromRgba` (entry, may return early) |
| Cursor kept in the window while flying | `cursorlock` (off) | `AltitudeGame.onFocusChanged`, `AltitudeGame.shutdown` (entry) |
| Shared per-frame hook | – | `AltitudeGame.render` (entry) |

`GLFrame`, `Font`, `ImagePack` and `ImageManager` come from `EmWrappers-1.1.9.jar`. Their patched copies go into the
client jar, which comes first on the launcher class path, so they shadow the library copies; the library jar itself is
not changed. Options are saved in `%LOCALAPPDATA%\NimblyGames\Altitude\config\client.properties` (only the ones changed
from their default).

## Build

Requirements: Windows, Java 17 or newer on `PATH`, and Altitude 1.1.9. The compiler (Eclipse ecj 3.38.0) and ASM 9.7.1
are in `tools/`. The scripts find Altitude in your Steam libraries; otherwise pass `-GameDir <Altitude folder>` or set
`ALTITUDE_DIR`.

```powershell
.\build.ps1             # out\Altitude-1.1.9-REMASTERED.jar
.\build.ps1 -TestJar    # also the offline test jar
.\bundle.ps1            # the release bundle, named after the build ID
```

The build:

1. Copies the stock game jar and the libraries listed in `app\Altitude.cfg` into `stock\lib` (once; the game folder is
   only read), and checks the SHA-256 of `Altitude-1.1.9.jar` and `EmWrappers-1.1.9.jar`.
2. Compiles `build-tools/`, `src/` and `test/` to Java 17 class files and runs the tests.
3. Writes the client jar with `client.build.Patcher`. It fails if a hook site is missing or found a different number of
   times, or if a class is not assigned to a fix. Every written class goes through ASM's `CheckClassAdapter`, and a
   build ID (commit and date) is written into the jar.
4. Loads every changed class with `-Xverify:all` on the launcher's class path (`client.build.LoadCheck`).
5. Compares the jar with stock (`client.build.JarDiff`) and writes `out\jar-diff.txt`, grouped by fix. Stock entries
   must be byte-identical, and patched classes may differ only at the hook sites.

## Testing

`.\build.ps1 -TestJar` also writes `out\Altitude-1.1.9-REMASTERED-test.jar`: the release jar plus the test-only
classes in `test-src/`. With `-Dclient.offline=true` it starts in the stock offline mode, without Steam or an account.
Its hooks are added only by `Hooks.enableTestBuild()` and listed in `out\jar-diff-test.txt`.

`test\visual.ps1` runs the test jar unattended: it starts the client offline in its own data folder (`out\test-game`),
plays a local skirmish against bots, and takes screenshots from inside the game. Results go to `out\visual\<time>`.

```powershell
.\test\visual.ps1 -Build -Pack out\Altitude-1.1.9-hdsprites.zip   # galleries and a match, HD sprites off and on
.\test\visual.ps1 -Reuse                                          # attach to a running test client (sprite-only runs)
.\test\visual.ps1 -Perf 60                                        # frame pacing on/off measurement with perf logs
```

It does not run while Altitude itself is running. Don't minimise the test window: the game stops rendering while
minimised.

## HD sprites

The art ships as `Altitude-1.1.9-hdsprites.zip`, read from beside the client jar. Each PNG is named by the content key
of the stock texture it replaces (byte count and CRC-32 of its pixels), so it can only replace that exact texture. The
pack's `manifest.txt` holds the format and game version, the SHA-256 of every PNG (checked when read), where each
texture is in the game's packs, and how the art was made. The release jar accepts only the official pack: `pack.ps1`
writes the SHA-256 of its manifest into `SpritePack.java`. Only the test jar accepts other packs, and loose PNGs in
`config\client-sprites` for trying new art.

The client uploads only the scale the window needs (stock up to 720 lines, 2x up to 1440, the pack's 3x above).
Worker threads first convert the art for that scale, mipmaps included, into `config\client-sprites-cache`, then the
textures reload once.

### Making the art

Windows, a Vulkan GPU, and [waifu2x-ncnn-vulkan](https://github.com/nihui/waifu2x-ncnn-vulkan) 20250915:

```powershell
.\sprite-tools\export.ps1 -Out <stock dir>
.\sprite-tools\upscale.ps1 -Stock <stock dir> -Out <masters dir> -Waifu2x <waifu2x folder> [-Tiled key,...]
.\sprite-tools\pack.ps1
```

1. `export.ps1` writes every stock texture as a PNG named by its key, plus an index of where each one is. Only the
   world art is upscaled (`render/planes`, `render/skins`, `render/map`, `render/powerups`, `planes/`, `effects/`,
   `map/`); `-Tiled` takes the keys of textures the game repeats.
2. `upscale.ps1` runs waifu2x (cunet model, 4x, no denoise), then `BackProject`, which corrects the result until
   shrinking it back gives the stock pixels exactly, so the upscaler cannot move markings or change colours. Repeating
   textures are upscaled as a 3x3 tiling (`TileWrap`) so tiles meet without seams.
3. `pack.ps1` shrinks the 4x masters to 3x, runs the fidelity check (it stops if any texture drifts from stock), and
   writes a reproducible pack: the same PNGs always give the same SHA-256.

If waifu2x writes blank or cropped images, try a smaller tile size (`-Tile 128`); the fidelity check catches them.

The art is in `art/hd-sprites`: `masters/` (the 4x upscales), `stock/` (the stock textures they were made from),
`index.txt` and `tiled.txt`. `pack.ps1` reads them by default. The pack itself is a build output and is not in git.

## Fonts

The sharp-font fix draws Blambot Casual from the two `.ttf` files in `fonts/`, which the build puts inside the client
jar. Verdana, Arial and Courier New come from Windows.

## Java 21 runtime (optional)

The game ships with a Java 17 runtime in `<Altitude>\runtime`. `install\java21-runtime.ps1` swaps it for a Java 21 JRE
with generational ZGC and a fixed 2 GB heap, and can roll it back:

```powershell
.\install\java21-runtime.ps1 -Jre <Temurin JRE 21 folder>
.\install\java21-runtime.ps1 -Rollback
```

It renames the old runtime to `runtime-before-java21-<time>`, backs up `app\Altitude.cfg`, and deletes nothing.

## Layout

```
build.ps1, bundle.ps1        build, test, patch, verify, diff; release bundle
build-tools/                 Hooks (the patch surface), Patcher, LoadCheck, JarDiff
src/                         client classes
test/, test-src/             tests run by the build, visual test; test-jar-only classes
sprite-tools/, art/          HD sprite pipeline and art
install/                     installer and Java 21 runtime script
tools/                       ecj 3.38.0, ASM 9.7.1, game-dir.ps1
```
