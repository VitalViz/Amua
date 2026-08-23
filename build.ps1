<#
.SYNOPSIS
    Builds and optionally runs Amua from the command line.

.DESCRIPTION
    Compiles every .java file under src/ into bin/, copies the non-Java
    resources (images, fonts, language files) that the app loads from the
    classpath at runtime, and can package the result as a runnable jar.

    Requires a JDK 11 or later on the PATH.  Java 11 is the floor because the
    bundled Khmer and Chinese fonts are registered at runtime via
    Font.createFont, which needs 11+ (see lang/Language.java).

    Dependency jars live in lib/ and are not tracked by git.  If lib/ is empty,
    run this script with -GetLibs to download them from Maven Central.

.EXAMPLE
    .\build.ps1                 # compile
    .\build.ps1 -Run            # compile, then launch Amua
    .\build.ps1 -Jar            # compile, then build dist/Amua-<version>.jar
    .\build.ps1 -GetLibs        # download the dependency jars, then compile
    .\build.ps1 -Clean          # delete bin/ and dist/ first
#>
param(
	[switch]$Run,
	[switch]$Jar,
	[switch]$Clean,
	[switch]$GetLibs
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$src  = Join-Path $root "src"
$bin  = Join-Path $root "bin"
$lib  = Join-Path $root "lib"
$dist = Join-Path $root "dist"

# The jars the project compiles and runs against.  jfreechart must stay on the
# 1.0.x line: 1.5 removed org.jfree.ui and changed the chart API.
$jars = @(
	"org/jfree/jfreechart/1.0.19/jfreechart-1.0.19.jar",
	"org/jfree/jcommon/1.0.23/jcommon-1.0.23.jar",
	"org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar",
	# JAXB: part of the JDK through Java 8, a separate dependency from 11 on
	"javax/xml/bind/jaxb-api/2.3.1/jaxb-api-2.3.1.jar",
	"org/glassfish/jaxb/jaxb-runtime/2.3.9/jaxb-runtime-2.3.9.jar",
	"org/glassfish/jaxb/txw2/2.3.9/txw2-2.3.9.jar",
	"com/sun/istack/istack-commons-runtime/3.0.12/istack-commons-runtime-3.0.12.jar",
	"org/jvnet/staxex/stax-ex/1.8.3/stax-ex-1.8.3.jar",
	"com/sun/xml/fastinfoset/FastInfoset/1.2.18/FastInfoset-1.2.18.jar",
	"javax/activation/javax.activation-api/1.2.0/javax.activation-api-1.2.0.jar"
)

function Get-Libs {
	if (-not (Test-Path $lib)) { New-Item -ItemType Directory $lib | Out-Null }
	foreach ($path in $jars) {
		$name = Split-Path $path -Leaf
		$dest = Join-Path $lib $name
		if (Test-Path $dest) { Write-Host "  have $name"; continue }
		Write-Host "  get  $name"
		Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/$path" -OutFile $dest
	}
}

# --- checks -----------------------------------------------------------------

$javac = Get-Command javac -ErrorAction SilentlyContinue
if ($null -eq $javac) {
	throw "javac was not found on the PATH. Install a JDK 11 or later (e.g. Eclipse Temurin) and reopen the terminal."
}
$verLine = (& javac -version 2>&1) -join " "
if ($verLine -match "(\d+)") {
	if ([int]$Matches[1] -lt 11) { throw "Found $verLine. Amua needs JDK 11 or later." }
}
Write-Host "Using $verLine"

if ($GetLibs) { Write-Host "Dependencies:"; Get-Libs }

$missing = @()
foreach ($path in $jars) {
	$name = Split-Path $path -Leaf
	if (-not (Test-Path (Join-Path $lib $name))) { $missing += $name }
}
if ($missing.Count -gt 0) {
	throw "Missing dependency jars in lib/: $($missing -join ', ').  Run: .\build.ps1 -GetLibs"
}

if ($Clean) {
	Write-Host "Cleaning"
	if (Test-Path $bin)  { Remove-Item -Recurse -Force $bin }
	if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
}

# --- compile ----------------------------------------------------------------

if (-not (Test-Path $bin)) { New-Item -ItemType Directory $bin | Out-Null }

$classpath = (Get-ChildItem (Join-Path $lib "*.jar") | ForEach-Object { $_.FullName }) -join ";"
$sources   = Get-ChildItem -Recurse -Filter *.java $src | ForEach-Object { $_.FullName }
Write-Host "Compiling $($sources.Count) source files"

# The whole source tree is UTF-8.  --release 11 pins the bytecode level so the
# build does not silently depend on APIs newer than the declared floor.
$argFile = Join-Path $env:TEMP "amua-sources.txt"
# UTF-8 with no byte order mark: javac rejects an argument file that starts with one
[System.IO.File]::WriteAllLines($argFile, $sources, (New-Object System.Text.UTF8Encoding($false)))
& javac -encoding UTF-8 --release 11 -nowarn -cp $classpath -d $bin "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "Compilation failed." }
Remove-Item $argFile

# --- resources --------------------------------------------------------------
# Amua loads icons, fonts and language files off the classpath with
# getResourceAsStream, so everything that is not a .java file has to sit
# alongside the .class files.  Eclipse does this automatically; javac does not.

Write-Host "Copying resources"
foreach ($from in @($src, (Join-Path $root "resources"))) {
	if (-not (Test-Path $from)) { continue }
	Get-ChildItem -Recurse -File $from | Where-Object { $_.Extension -ne ".java" } | ForEach-Object {
		$rel    = $_.FullName.Substring($from.Length + 1)
		$target = Join-Path $bin $rel
		$parent = Split-Path $target -Parent
		if (-not (Test-Path $parent)) { New-Item -ItemType Directory $parent -Force | Out-Null }
		Copy-Item $_.FullName $target -Force
	}
}

Write-Host "Build complete -> $bin"

# --- package ----------------------------------------------------------------

if ($Jar) {
	$amua = Get-Content (Join-Path $src "main\Amua.java") | Select-String 'String version="([^"]+)"'
	$version = $amua.Matches[0].Groups[1].Value
	if (-not (Test-Path $dist)) { New-Item -ItemType Directory $dist | Out-Null }

	# Build a single self-contained jar with the dependencies unpacked into it, the way the
	# official Amua releases are shipped.  A jar that instead REFERENCES lib/ from its manifest
	# breaks the moment someone moves or copies the jar on its own: JAXB then fails to load with
	# a NoClassDefFoundError, which is an Error rather than an Exception, so frmMain.openModel
	# never reaches its catch block, never resets the wait cursor, and the app looks hung.
	Write-Host "Packaging (self-contained)"
	$stage = Join-Path $dist "_stage"
	if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
	New-Item -ItemType Directory $stage | Out-Null

	# Amua's own classes and resources first, so they always win over anything in a dependency
	Copy-Item (Join-Path $bin "*") $stage -Recurse -Force

	$skipPattern = '(?i)^META-INF/([^/]+\.(SF|DSA|RSA|EC)|INDEX\.LIST|MANIFEST\.MF)$'
	foreach ($jarPath in (Get-ChildItem (Join-Path $lib "*.jar"))) {
		$tmp = Join-Path $env:TEMP ("amua-unpack-" + $jarPath.BaseName)
		if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
		New-Item -ItemType Directory $tmp | Out-Null
		Push-Location $tmp
		& jar --extract --file $jarPath.FullName
		Pop-Location
		if ($LASTEXITCODE -ne 0) { throw "Could not unpack $($jarPath.Name)" }

		Get-ChildItem -Recurse -File $tmp | ForEach-Object {
			$rel = $_.FullName.Substring($tmp.Length + 1)
			$key = $rel -replace '\\', '/'

			# signatures break once the archive is repacked; per-jar manifests must not survive
			if ($key -match $skipPattern) { return }
			# a classpath jar ignores module descriptors, and a stale one confuses tooling
			if ($key -eq "module-info.class") { return }

			$target = Join-Path $stage $rel
			if ($key -like "META-INF/services/*") {
				# ServiceLoader registrations must be merged, not overwritten: this is how
				# JAXBContext finds the RI implementation at runtime
				$parent = Split-Path $target -Parent
				if (-not (Test-Path $parent)) { New-Item -ItemType Directory $parent -Force | Out-Null }
				if (Test-Path $target) {
					Add-Content -Path $target -Value ([System.IO.File]::ReadAllText($_.FullName))
				} else {
					Copy-Item $_.FullName $target -Force
				}
				return
			}

			# first jar wins for anything else that collides (licences, notices)
			if (Test-Path $target) { return }
			$parent = Split-Path $target -Parent
			if (-not (Test-Path $parent)) { New-Item -ItemType Directory $parent -Force | Out-Null }
			Copy-Item $_.FullName $target -Force
		}
		Remove-Item -Recurse -Force $tmp
	}

	$manifest = Join-Path $env:TEMP "amua-manifest.txt"
	Set-Content -Path $manifest -Encoding ascii -Value @("Main-Class: main.Amua", "")

	# Underscore, matching the naming of the published Amua release assets (Amua_0.3.6.jar)
	$jarFile = Join-Path $dist "Amua_$version.jar"
	if (Test-Path $jarFile) { Remove-Item $jarFile -Force }
	& jar --create --file $jarFile --manifest $manifest -C $stage .
	if ($LASTEXITCODE -ne 0) { throw "Packaging failed." }
	Remove-Item $manifest
	Remove-Item -Recurse -Force $stage

	$mb = [Math]::Round((Get-Item $jarFile).Length / 1MB, 1)
	Write-Host "Packaged -> $jarFile ($mb MB, self-contained)"
	Write-Host "Run it with: java -jar `"$jarFile`""
}

# --- run --------------------------------------------------------------------

if ($Run) {
	Write-Host "Launching Amua"
	& java -cp "$bin;$classpath" main.Amua
}
