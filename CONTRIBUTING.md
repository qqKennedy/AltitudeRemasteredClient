# Contributing

Bug reports and performance results go through the [issue forms](../../issues/new/choose). Pull requests are welcome.

## Rules

- **No gameplay changes.** The client must play exactly like stock on any server: display, performance and
  convenience only.
- **Every change to the game is listed** in [`Hooks.java`](build-tools/src/client/build/Hooks.java); the build fails
  on any unlisted difference from the stock jar.
- **Fail to stock.** If a fix hits something unexpected, it falls back to stock behaviour.

## Building

You need Windows, Java 17 or newer, and Altitude 1.1.9 from Steam.

```powershell
.\build.ps1                   # out\Altitude-1.1.9-REMASTERED.jar (also runs the tests)
.\installer\build-setup.ps1   # out\Altitude-REMASTERED-Setup.exe
```

More detail is in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Licence

Contributions are licensed under the GPL-3.0, like the rest of this repository's code.
