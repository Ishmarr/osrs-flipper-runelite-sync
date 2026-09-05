# Return only a suitable IntelliJ-managed JDK path. Never change user/machine settings.
$jdkDirectory = Join-Path $env:USERPROFILE '.jdks'
$candidates = foreach ($directory in (Get-ChildItem -LiteralPath $jdkDirectory -Directory -ErrorAction SilentlyContinue)) {
    $release = Join-Path $directory.FullName 'release'
    if (!(Test-Path -LiteralPath (Join-Path $directory.FullName 'bin\java.exe')) -or
        !(Test-Path -LiteralPath (Join-Path $directory.FullName 'bin\javac.exe')) -or
        !(Test-Path -LiteralPath $release)) { continue }
    $versionLine = Get-Content -LiteralPath $release -ErrorAction SilentlyContinue |
        Where-Object { $_ -match '^JAVA_VERSION="([0-9]+)(?:[."]|[-+])' } | Select-Object -First 1
    if ($versionLine -match '^JAVA_VERSION="([0-9]+)' -and [int]$Matches[1] -ge 17) {
        [pscustomobject]@{ Path = $directory.FullName; Major = [int]$Matches[1] }
    }
}
$selected = $candidates | Sort-Object Major, Path -Descending | Select-Object -First 1
if ($null -eq $selected) { exit 1 }
$selected.Path
