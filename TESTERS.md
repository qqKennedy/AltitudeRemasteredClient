# Testing Altitude REMASTERED

## Install

Close Altitude, run `Altitude-REMASTERED-Setup.exe`, press **Install**. To remove it, run it again and press
**Uninstall**.

Windows may say "Windows protected your PC": click **More info**, then **Run anyway** (the exe isn't code-signed).

## What to try

Type `/opt` in the game console: the first line is the build ID, then every option. Please try:

- **Frame pacing** (`/opt framepacing on`, off by default): does it feel smoother? Tell us your frame-rate cap and
  screen refresh rate.
- **Borderless window** (`/opt window borderless`, `/opt monitor 2`, then restart): does it fill your second monitor?
- **Cursor lock** (`/opt cursorlock play`): the cursor stays in the game while flying, and is free in menus, chat and
  after alt-tab. If it ever stays stuck, Win+L frees it.

## If something's wrong

| Problem | Try |
|---|---|
| Sprites look wrong, or stutter | `/opt hdsprites off` |
| Text looks wrong | `/opt sharpfonts off` |
| The game won't start, or crashes | run Setup, untick **Java 21**, Install |
| Sound crackles or stops (for example after plugging in a headset) | run Setup, untick **audio library**, Install |

Then [report it](https://github.com/qqKennedy/AltitudeRemasteredClient/issues/new/choose) with the build ID and your
graphics card. For performance, turn on `/opt perfhud on` and `/opt perflog on` first, and attach
`%LOCALAPPDATA%\NimblyGames\Altitude\config\client-perf.csv`.

Steam's "Verify integrity of game files" can leave a half-removed install: run Setup again to repair it.
