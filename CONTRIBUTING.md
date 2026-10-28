# Contributing

Thanks for helping. Bug reports and performance results go through the [issue forms](../../issues/new/choose).
This page is for changes to the code.

## Ground rules

- **No gameplay changes.** The client must play exactly like stock against any server: no changes to physics,
  networking, game rules, or anything that shows more than the stock client does. Display, performance and
  convenience only.
- **Every change to the game is listed.** The patched jar is the stock `Altitude-1.1.9.jar` plus hooks. Each hook
  site and each new class belongs to a named fix in
  [`build-tools/src/client/build/Hooks.java`](build-tools/src/client/build/Hooks.java), and the build fails on any
  difference from stock that is not listed there (`out\jar-diff.txt` shows the result).
- **Fail to stock.** A fix that hits something unexpected falls back to the stock behaviour and logs it once; it
  never breaks a frame or a match.
- **Test-only stays test-only.** Anything for testing (offline start, the test harness, loose sprite PNGs) lives in
  `test-src/` and is added only by `Hooks.enableTestBuild()`, never in the release jar.

## Build and test

Requirements: Windows, Java 17 or newer on `PATH`, and Altitude 1.1.9 from Steam (the build reads the stock jars from
your install and never writes to it).

```powershell
.\build.ps1            # compile, run the tests, patch, verify, diff: out\Altitude-1.1.9-REMASTERED.jar
.\build.ps1 -TestJar   # also the offline test jar
.\test\visual.ps1 -Pack out\Altitude-1.1.9-hdsprites.zip   # unattended in-game screenshots, HD sprites off and on
.\test\visual.ps1 -Perf 60                                 # frame pacing measurement (see the script's help)
.\bundle.ps1           # the playtest zip, named after the build ID
```

The tests in `test\` run on every build; each `*Test` class has a `main` that throws on failure. The visual test
starts the offline test client in its own data folder (`out\test-game`), plays a bot match and screenshots it from
inside the game, so it never signs in or touches your own settings. Don't minimise its window while it runs.

## Making a change

- One fix per commit. Say in the message what it changes and why, and end with how to check it (`Check:` a test, a
  visual test run, or what to look at in game).
- New hooks: add the site to `Hooks.java` under its fix, keep the hook a single static call, and do the work in a
  class in `src/em/altitude/game/scene/player`. Options go in `ClientOptions.ALL` (first value is the default).
- Match the surrounding code's style and comment density.

## HD sprite art

The art lives in `art/hd-sprites` (4x masters and their stock sources). `sprite-tools/` has the pipeline (export,
waifu2x upscale, back-projection, fidelity check, pack); see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md#making-the-art). `pack.ps1` writes the
pack's manifest SHA-256 into `SpritePack.java`: the release jar accepts only that pack, so a new pack always needs a new
client build.

## Licence

By contributing you agree that your contribution is licensed under the GPL-3.0, like the rest of this repository's
code (see the Licence section of the README for what that does not cover).
