<#
.SYNOPSIS
    Audits a patched APK (or a .mpp bundle) for the failure modes a green patch run does not catch.
    Each check names the incident it exists for.

.DESCRIPTION
    Checks, in order:

      1. stale bundle      Every extension call injected *into app code* must be described by the
                           patches/src sources on disk. An injection present in the APK but absent from
                           the sources means the APK was built from different sources -- the incident
                           this exists for: the `check-cast` fix was in the source while the .mpp had
                           been built with those files moved aside, so a green run shipped the crash.
      2. reachability      For every app method we injected into: how many app methods call it, and
                           does its name appear in a native library? A hook nothing calls may control
                           nothing -- "Disable DRC audio" installed perfectly and nothing ever read its
                           getter (see INTERNALS.md). A review list, never a failure: lambdas are
                           invoked through call sites and legitimately show 0.
      3. cast lint         Injected blocks that take an object result with no `check-cast`. The
                           VideoQuality fix needed one; without it the app launched and died at the
                           first video with a VerifyError.
      4. settings XML      Decodes resources and fails on an unqualified `@name` reference (the encoder
                           stores it as a raw string, and the hub then crashes on inflation), and
                           prints the hub rows so a summary the extension overwrites is visible.
      5. bundle mode       `-Bundle <mpp>` checks the half that needs no stock APK, so CI can run it:
                           every extension reference inside the bundle's own patch templates must
                           exist in the bundle's extension dex.

    Exit code 1 if any check fails; warnings alone do not fail the run.

.EXAMPLE
    pwsh -NoProfile -File scripts\audit-patched-apk.ps1                        # full audit
    pwsh -NoProfile -File scripts\audit-patched-apk.ps1 -SkipXml -SkipReach     # what the install script runs
    pwsh -NoProfile -File scripts\audit-patched-apk.ps1 -Bundle patches\build\libs\patches-1.24.0.mpp
#>
[CmdletBinding()]
param(
    [string] $Apk = 'apk\ytvr-patched.apk',
    [string] $Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path,
    [string] $Bundle = '',
    [string] $Work = '',
    [switch] $SkipReach,
    [switch] $SkipXml
)

$ErrorActionPreference = 'Stop'
if (-not $Work) { $Work = Join-Path $Root 'build\probe\audit' }
New-Item -ItemType Directory -Force -Path $Work | Out-Null

$script:failures = 0
$script:warnings = 0
function Write-Line([string] $kind, [string] $text) {
    $colour = switch ($kind) {
        'FAIL' { 'Red' } 'WARN' { 'Yellow' } 'PASS' { 'Green' } 'INFO' { 'DarkGray' } default { 'Gray' }
    }
    Write-Host ("[{0}] {1}" -f $kind, $text) -ForegroundColor $colour
}
function Fail([string] $text) { Write-Line 'FAIL' $text; $script:failures++ }
function Warn([string] $text) { Write-Line 'WARN' $text; $script:warnings++ }
function Pass([string] $text) { Write-Line 'PASS' $text }
function Info([string] $text) { Write-Line 'INFO' $text }

$text = [System.Text.Encoding]::GetEncoding('ISO-8859-1')
$extensionRefPattern = 'Lapp/ytvr/extension/[A-Za-z0-9_$]+;->[A-Za-z0-9_$<>]+'

# Extracts every matching zip entry into $Into and returns how many were written. Output is a single
# integer on purpose: PowerShell returns everything a function prints, and a caller that receives an
# array where it expects a value is a debugging session (that is why the JDK setup below is inline).
function Extract-ApkEntries([string] $Archive, [string] $Pattern, [string] $Into) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    New-Item -ItemType Directory -Force -Path $Into | Out-Null
    $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $Archive).Path)
    try {
        $entries = @($zip.Entries | Where-Object { $_.FullName -match $Pattern })
        foreach ($entry in $entries) {
            $name = Split-Path $entry.FullName -Leaf
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $Into $name), $true)
        }
        return $entries.Count
    } finally { $zip.Dispose() }
}

# ---------------------------------------------------------------------------------------------
# 5. Bundle mode: the half CI can run, since it needs no stock APK.
# ---------------------------------------------------------------------------------------------
if ($Bundle) {
    Write-Line 'INFO' "bundle mode: $Bundle"
    $tmp = Join-Path $Work 'bundle'
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Extract-ApkEntries $Bundle '\.dex$' $tmp | Out-Null
    $extensionDex = Get-ChildItem $tmp -Filter '*.dex' | Where-Object { $_.Name -match 'extension' } | Select-Object -First 1
    if (-not $extensionDex) {
        Fail 'the bundle has no *extension*.dex: what the patches inject cannot exist'
        exit 1
    }
    $references = New-Object System.Collections.Generic.HashSet[string]
    foreach ($dex in (Get-ChildItem $tmp -Filter '*.dex' | Where-Object { $_.Name -ne $extensionDex.Name })) {
        foreach ($m in [regex]::Matches($text.GetString([System.IO.File]::ReadAllBytes($dex.FullName)), $extensionRefPattern)) {
            [void]$references.Add($m.Value)
        }
    }
    $extensionText = $text.GetString([System.IO.File]::ReadAllBytes($extensionDex.FullName))
    $missing = @()
    foreach ($reference in $references) {
        $member = ($reference -split ';->')[1]
        if (-not $extensionText.Contains("$member")) { $missing += $reference }
    }
    if ($missing.Count -gt 0) {
        Fail ("patch templates call $($missing.Count) extension member(s) the extension dex does not have: " + (($missing | Select-Object -First 5) -join ', '))
    } else {
        Pass "all $($references.Count) extension reference(s) in the patch templates exist in the extension dex"
    }
    exit $(if ($failures -gt 0) { 1 } else { 0 })
}

# ---------------------------------------------------------------------------------------------
# APK mode. JDK + dexlib2 first: the reader uses the patcher's own dexlib2 (from the Gradle cache),
# so the audit and the patcher agree on what a dex contains.
# ---------------------------------------------------------------------------------------------
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\javac.exe' } else { '' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { '' }
if (-not (Test-Path $javac)) { $javac = (Get-Command javac -ErrorAction SilentlyContinue).Source }
if (-not (Test-Path $java)) { $java = (Get-Command java -ErrorAction SilentlyContinue).Source }
if (-not $javac -or -not $java) { throw 'no JDK found: set JAVA_HOME (javac builds tools\dexaudit)' }

$cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
$jars = @()
foreach ($group in @('com.github.MorpheApp.smali\smali-dexlib2', 'com.github.MorpheApp.smali\smali-util', 'com.google.guava\guava')) {
    $dir = Join-Path $cache $group
    if (Test-Path $dir) {
        $jars += @(Get-ChildItem -Path $dir -Recurse -Filter '*.jar' | Where-Object { $_.Name -notmatch 'sources|javadoc' } | ForEach-Object { $_.FullName })
    }
}
if ($jars.Count -eq 0) { throw 'dexlib2/guava not in the Gradle cache: run a build once so Gradle fetches them' }
$classpath = ($jars -join ';')

$out = Join-Path $Work 'dexaudit'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$toolSource = Join-Path $Root 'tools\dexaudit\DexAudit.java'
$toolClass = Join-Path $out 'DexAudit.class'
if (-not (Test-Path $toolClass) -or (Get-Item $toolSource).LastWriteTime -gt (Get-Item $toolClass).LastWriteTime) {
    & $javac -cp $classpath -d $out $toolSource | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'tools\dexaudit\DexAudit.java did not compile' }
}
$toolClasspath = "$out;$classpath"

$dexes = Join-Path $Work 'dex'
$libs = Join-Path $Work 'lib'
if (Test-Path $dexes) { Remove-Item $dexes -Recurse -Force }
$dexCount = Extract-ApkEntries $Apk '^classes\d*\.dex$' $dexes
if ($dexCount -eq 0) { Fail "$Apk has no classes*.dex"; exit 1 }
Extract-ApkEntries $Apk '^lib/arm64-v8a/.*\.so$' $libs | Out-Null
$dexFiles = @(Get-ChildItem $dexes -Filter '*.dex' | ForEach-Object { $_.FullName })
Info "auditing $Apk ($dexCount dex file(s))"

# Every extension call injected into app code, keyed by the app method it was injected into: this is
# the inventory a human reconciles with the patch list, and the input to the reachability check.
$sites = [ordered]@{}
foreach ($line in (& $java -cp $toolClasspath DexAudit inventory @dexFiles)) {
    $parts = $line -split '\|'
    $sites[$parts[0]] = $parts[2]
}

# 1. The shipped extension dex, against the extension sources on disk.
#    A member the sources define but the dex lacks is the incident this exists for: a .mpp built while
#    those sources were moved aside still reported a green patch run, and shipped a crash fix that was
#    not in it. (Comparing *references* instead does not work -- the sources compose them from
#    EXTENSION_CLASS constants, so the literal text never appears there.)
$extDex = $null
foreach ($dex in $dexFiles) {
    if ($text.GetString([System.IO.File]::ReadAllBytes($dex)).Contains('Lapp/ytvr/extension/CategoryRows;')) {
        $extDex = $dex
        break
    }
}
if (-not $extDex) {
    Fail 'no extension dex in the APK: the Extension patch did not run, or it was built without one'
} else {
    $extText = $text.GetString([System.IO.File]::ReadAllBytes($extDex))
    $declaredClasses = New-Object System.Collections.Generic.HashSet[string]
    $declaredMembers = New-Object System.Collections.Generic.HashSet[string]
    foreach ($file in (Get-ChildItem (Join-Path $Root 'extensions\src') -Filter '*.java')) {
        # Not $java: that is the JDK's java.exe, and shadowing it makes the tool invocation below try
        # to execute this source text as a command.
        $javaText = [System.IO.File]::ReadAllText($file.FullName)
        foreach ($m in [regex]::Matches($javaText, '(?m)^\s*(?:public\s+|final\s+|abstract\s+|static\s+)*(?:class|interface)\s+([A-Za-z0-9_]+)')) {
            [void]$declaredClasses.Add($m.Groups[1].Value)
        }
        foreach ($m in [regex]::Matches($javaText, 'public\s+static\s+[A-Za-z0-9_<>,\.\[\]\s]*?([A-Za-z0-9_]+)\s*\(')) {
            [void]$declaredMembers.Add($m.Groups[1].Value)
        }
    }
    $missing = @(@($declaredClasses | Where-Object { -not $extText.Contains($_) }) + @($declaredMembers | Where-Object { -not $extText.Contains($_) }))
    if ($missing.Count -gt 0) {
        Fail ("the shipped extension dex lacks $($missing.Count) member(s) the sources define -- this APK is not from this tree: " + (($missing | Select-Object -First 8) -join ', '))
    } else {
        Pass "the shipped extension dex carries all $($declaredClasses.Count) class(es) and $($declaredMembers.Count) static method(s) the sources declare"
    }
}

# The inventory is also the input to the reachability check below.
Info "injected into $($sites.Count) app method(s):"
foreach ($site in @($sites.Keys)) {
    Write-Host ("        {0}  <-  {1}" -f $site, $sites[$site])
}

# 2. Reachability of each hooked method.
if (-not $SkipReach) {
    Info 'reachability: a hook nothing calls may control nothing (the DRC lesson)'
    $libText = ''
    foreach ($so in @(Get-ChildItem $libs -Filter '*.so' -ErrorAction SilentlyContinue)) {
        $libText += $text.GetString([System.IO.File]::ReadAllBytes($so.FullName))
    }
    foreach ($site in @($sites.Keys)) {
        $owner, $method = $site -split '#'
        $callers = @(& $java -cp $toolClasspath DexAudit callers "$owner;->$method(" @dexFiles | Where-Object { $_ -notlike "$site|*" })
        $native = $libText.Contains($method)
        if ($callers.Count -eq 0 -and -not $native) {
            Warn "$site -- no dex caller and '$method' is in no native library: prove why this hook exists"
        } else {
            Info "$site -- callers=$($callers.Count) native=$native"
        }
    }
}

# 3. Injected blocks that take an *object* result with no check-cast. The VideoQuality fix needed one;
#    without it the app launched and died at the first video (the verifier rejects the class on first
#    use, not at launch). Only `Ljava/lang/Object;` returns are flagged -- a String-returning call
#    moved back into the register it came from needs no cast, and flagging that is how a lint gets
#    ignored.
foreach ($file in (Get-ChildItem (Join-Path $Root 'patches\src') -Recurse -Filter '*.kt')) {
    $fileText = [System.IO.File]::ReadAllText($file.FullName)
    foreach ($block in [regex]::Matches($fileText, '(?s)"""(.*?)"""')) {
        $body = $block.Groups[1].Value
        if ($body -match '\)Ljava/lang/Object;' -and $body -match 'move-result-object' -and $body -notmatch 'check-cast') {
            $line = ($fileText.Substring(0, $block.Index) -split "`n").Count
            $call = ([regex]::Match($body, 'invoke-[a-z/]+[^\r\n]*\)Ljava/lang/Object;')).Value.Trim()
            Warn "$($file.Name):$line an injected call returns Ljava/lang/Object; with no check-cast in the block ($call) -- in a typed field that fails verification at first use"
        }
    }
}

# 4. The settings XML.
if (-not $SkipXml) {
    $jadx = (Get-Command jadx -ErrorAction SilentlyContinue).Source
    if (-not $jadx) {
        # The README's tooling table says "on PATH"; in practice it is usually unpacked under tools\.
        foreach ($candidate in @(
                (Join-Path $env:USERPROFILE 'tools\jadx\bin\jadx.bat'),
                (Join-Path $env:USERPROFILE 'tools\jadx\bin\jadx'))) {
            if (Test-Path $candidate) { $jadx = $candidate; break }
        }
    }
    if (-not $jadx) {
        Info 'jadx is not on PATH: skipped the settings-XML check'
    } else {
        $res = Join-Path $Work 'res'
        if (Test-Path $res) { Remove-Item $res -Recurse -Force }
        & $jadx --no-src -d $res $Apk *> (Join-Path $Work 'jadx.log')
        $xmls = @(Get-ChildItem (Join-Path $res 'resources\res\xml') -Filter '*.xml' -ErrorAction SilentlyContinue)
        $unqualified = @()
        foreach ($xml in $xmls) {
            foreach ($m in [regex]::Matches([System.IO.File]::ReadAllText($xml.FullName), '="(@[^"/<>]*)"')) {
                $unqualified += "$($xml.Name):$($m.Groups[1].Value)"
            }
        }
        if ($unqualified.Count -gt 0) {
            Fail ("unqualified resource reference(s) in the settings XML, which inflate to resource id 0: " + (($unqualified | Select-Object -Unique | Select-Object -First 5) -join ', '))
        } else {
            Pass "no unqualified '@' reference in $($xmls.Count) decoded settings XML file(s)"
        }
        $hub = $xmls | Where-Object { $_.Name -match 'settings_prefs|panel2' } | Select-Object -First 1
        if ($hub) {
            Info "hub rows in $($hub.Name) (a summary here that the extension overwrites is invisible advice):"
            $title = ''
            foreach ($line in (Get-Content $hub.FullName)) {
                if ($line -match 'android:title="([^"]*)"') { $title = $Matches[1] }
                if ($line -match 'android:key="([^"]*)"') { Write-Host ("        {0,-30} {1}" -f $title, $Matches[1]) }
                if ($line -match 'android:summary="([^"]*)"') { Write-Host ("        {0,-30} summary: {1}" -f '', $Matches[1]) }
            }
        }
    }
}

if ($failures -gt 0) {
    Write-Line 'FAIL' "$failures check(s) failed, $warnings warning(s)"
    exit 1
}
Write-Line 'PASS' "audit clean ($warnings warning(s) to review)"
exit 0
