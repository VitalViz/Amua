# Building Amua

This is the VitalViz fork of [Amua](https://github.com/zward/Amua). Builds of this fork carry a
`_vs` suffix so they are never mistaken for an upstream release of the same number.

## What you need

- **A JDK 21 or later** on the `PATH`. Built and tested with Eclipse Temurin 25.
  Java 11 is the hard floor (the bundled Khmer and Chinese fonts are registered at runtime and
  need it), but the packaging step uses `jpackage`, which ships with every JDK from 14 onwards.
- **Windows**, if you want the packaged application. `jpackage` cannot cross-build: a Windows
  package has to be built on Windows, a macOS one on a Mac.
- Nothing else. The dependencies are downloaded for you, and no part of this needs administrator
  rights.

You do not need to install `jpackage` separately. It is part of the JDK, at `<JDK>\bin\jpackage.exe`.

## First time

```powershell
.\build.ps1 -GetLibs
```

Downloads the ten dependency jars from Maven Central into `lib\`. They are deliberately not in
git. Run this once; later builds reuse them.

## Building

```powershell
.\build.ps1                  # compile into bin\
.\build.ps1 -Run             # compile, then launch
.\build.ps1 -Jar             # compile, then build dist\Amua_<version>.jar
.\build.ps1 -Jar -AppImage   # the above, plus the Windows application folder
.\build.ps1 -Clean -Jar      # start from scratch
```

| Output | What it is |
|---|---|
| `bin\` | Compiled classes plus the images, fonts and language files |
| `dist\Amua_<version>.jar` | One self-contained jar; needs a Java runtime to run |
| `dist\app-image\Amua\` | The Windows application: launcher, jar and a trimmed Java runtime, about 92 MB. Needs no Java installed |

## Releasing

1. Set the version in `src\main\Amua.java`. Keep the `_vs` suffix: `build.ps1` refuses to build
   without it, so a release can never accidentally claim to be the upstream one.
2. `.\build.ps1 -Clean -Jar -AppImage`
3. Zip `dist\app-image\Amua\` as `Amua_<version>_windows.zip`.
4. Tag and push: `git tag -a v<version> -m "Amua <version>"` then `git push origin v<version>`.
5. Attach both the jar and the zip to the GitHub release.

A user unzips the folder anywhere and runs `Amua.exe`. On first run Amua registers itself for
`.amua` files, under `HKEY_CURRENT_USER` only, so double-clicking a model opens it. Moving the
folder is repaired on the next start. `unregister-amua.bat` undoes it and `register-amua.bat`
restores it; both are copied into the package by the build.

## What is and is not in git

| | |
|---|---|
| In git | The source, `build.ps1`, this file, the `.bat` scripts in `packaging\windows\`, and `packaging\windows\Amua.ico` when there is one |
| Not in git | `lib\` (downloaded), `bin\` and `dist\` (built), the JDK and `jpackage` (a prerequisite), the bundled runtime (generated) |

## Notes

**The two icons.** Both live in `packaging\windows\` and both have to be `.ico`; Windows will not
take a PNG for either purpose.

- `Amua.ico` is passed to `jpackage` and **compiled into `Amua.exe`**, so it becomes the launcher,
  taskbar and Start menu icon. It is not a loose file in the package.
- `Amua Model.ico` is what Explorer draws on `.amua` files. This one cannot be compiled in,
  because the registry points at an icon **by path**, so the build copies it to `app\` inside the
  package and Amua registers that path. If it is missing, `.amua` files fall back to the launcher
  icon and the build says so.

Each should hold the usual run of sizes — 16, 24, 32, 48, 64, 96, 128 and 256 — because Windows
picks one per context, and 16 and 32 are the sizes people actually see.

Explorer caches file type icons hard. After a change, `ie4uinit.exe -ClearIconCache` and
restarting Explorer usually refreshes them; a reboot always does.

**Which modules go into the runtime.** `build.ps1` lists them explicitly, which is what keeps the
package near 92 MB rather than shipping a whole JDK. If a future change needs another module, the
application will fail to start with a `NoClassDefFoundError` naming it; add it to the list in the
`-AppImage` section.

**Reproducible means the same recipe, not identical bytes.** The bundled runtime comes from
whichever JDK runs the build, so a different JDK produces a different, equally working runtime.
Pin the JDK version if you need the output to match exactly.

**Eclipse.** The project also imports into Eclipse directly; `.classpath` expects the dependencies
in `lib\`, so run `-GetLibs` first there too.
