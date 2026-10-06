$ErrorActionPreference = 'Stop'
$p12Path = Join-Path $PSScriptRoot 'launcher-release.p12'
if (-not (Test-Path -LiteralPath $p12Path)) {
    throw "Signing keystore not found: $p12Path"
}
[Convert]::ToBase64String([IO.File]::ReadAllBytes($p12Path)) | Set-Clipboard
Write-Host 'ANDROID_KEYSTORE_BASE64 value copied to clipboard.'
Write-Host 'Add it as a GitHub Actions Repository secret. Read the password from keystore-password.txt.'
