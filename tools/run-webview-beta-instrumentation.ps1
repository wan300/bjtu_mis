param(
    [Parameter(Mandatory = $true)]
    [string]$WebViewBetaApk,
    [string]$Serial = "",
    [string]$AndroidHome = $env:ANDROID_HOME
)

$ErrorActionPreference = "Stop"

$resolvedApk = (Resolve-Path -LiteralPath $WebViewBetaApk).Path
if (-not $AndroidHome) {
    throw "ANDROID_HOME is not set. Pass -AndroidHome explicitly."
}
$adb = Join-Path $AndroidHome "platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw "adb.exe was not found at $adb"
}

$adbArgs = @()
if ($Serial) {
    $adbArgs += @("-s", $Serial)
}

& $adb @adbArgs wait-for-device
& $adb @adbArgs install -r $resolvedApk
if ($LASTEXITCODE -ne 0) {
    throw "WebView Beta APK installation failed."
}

try {
    & $adb @adbArgs shell cmd webviewupdate set-webview-implementation com.google.android.webview.beta
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to select com.google.android.webview.beta as the WebView provider."
    }
    Push-Location (Join-Path $PSScriptRoot "..\android")
    try {
        .\gradlew.bat :app:connectedDebugAndroidTest
        if ($LASTEXITCODE -ne 0) {
            throw "WebView Beta instrumentation failed."
        }
    } finally {
        Pop-Location
    }
} finally {
    & $adb @adbArgs shell cmd webviewupdate set-webview-implementation com.google.android.webview
}
