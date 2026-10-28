# Altitude REMASTERED: playtest notes

A patched Altitude 1.1.9 client: HD sprites, sharp HUD text, a resizable window and a clearer ball-mode score.

## Install

1. Close Altitude.
2. Unzip the bundle anywhere (right-click > Extract All).
3. In the extracted folder, right-click empty space, choose **Open in Terminal**, and run:

   ```
   powershell -ExecutionPolicy Bypass -File .\remastered.ps1
   ```

   It finds Altitude through Steam. If it can't, add `-GameDir "D:\SteamLibrary\steamapps\common\Altitude"` (your path).
4. Start Altitude from Steam as usual.

## Undo

Close Altitude and run:

```
powershell -ExecutionPolicy Bypass -File .\remastered.ps1 -Uninstall
```

Steam's **Verify integrity of game files** (Altitude > Properties > Installed Files) also undoes the install.

## What's new

Type `/opt` in the console. The first line is the build ID; please quote it in reports. Then each option:

- **Sharp fonts**: HUD text drawn at your screen's resolution (`stockfonts` off).
- **Score HUD**: a backing behind the ball-mode score and the sudden-death target (`scorehud`).
- **Resizable window**: drag windowed mode to any size or maximise it (`window`).
- **HD sprites**: upscaled art for the game world (`hdsprites`).

All of these are on by default. `/opt NAME` explains one; `/opt NAME on` or `off` changes it.

If sprites look wrong or the game stutters, try `/opt hdsprites off` and tell us whether that fixed it.

## Please test: frame pacing (off by default)

`/opt framepacing on` replaces the game's frame limiter with one that keeps every frame on a fixed schedule. With a
400 fps cap on a 120 Hz screen, frame times were about 4x steadier but it used about 30% more of one CPU core, and the
difference was too small to see. It may matter more on other setups:

1. Turn on `/opt perfhud on`, play a few minutes with `/opt framepacing off`, then a few minutes with it `on`.
2. Tell us whether it **felt** any different (smoother, less stutter, or nothing), and copy the perf HUD's two lines
   from each.
3. Tell us your **frame-rate cap** (graphics options), your **screen's refresh rate** (60, 120, 144, 240 Hz...), and
   your **CPU**, laptop or desktop.

Especially interesting: a frame-rate cap close to your refresh rate (for example 120 or 144 fps), and slower PCs. At a
very high cap (999 fps) it keeps a CPU core busy all the time, so leave it off there.

## Reporting

For performance, first turn on:

```
/opt perfhud on
/opt perflog on
```

Then play a few minutes and send us:

- what you saw, and the build ID from `/opt`;
- your graphics card (and screen resolution);
- the logs in `%LOCALAPPDATA%\NimblyGames\Altitude\logs`;
- `%LOCALAPPDATA%\NimblyGames\Altitude\config\client-perf.csv` (from `perflog`).

Paste `%LOCALAPPDATA%\NimblyGames\Altitude` into the Explorer address bar to open the folder.
