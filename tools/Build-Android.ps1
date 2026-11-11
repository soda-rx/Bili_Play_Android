param([Parameter(Mandatory)][string]$ToolchainFile, [switch]$VerifyRelease, [switch]$Deliver)
$ErrorActionPreference = 'Stop'
$androidRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$toolchain = Get-Content -LiteralPath $ToolchainFile -Raw | ConvertFrom-Json
$env:JAVA_HOME = $toolchain.javaHome
$env:ANDROID_HOME = $toolchain.sdk
$env:GRADLE_USER_HOME = $toolchain.cache
$env:ANDROID_PREFS_ROOT = Join-Path $toolchain.cache 'android-preferences'
$env:ANDROID_USER_HOME = Join-Path $env:ANDROID_PREFS_ROOT '.android'
New-Item -ItemType Directory -Path $env:ANDROID_USER_HOME -Force | Out-Null
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
New-Item -ItemType Directory -Path $toolchain.signingDirectory -Force | Out-Null
$env:BILIPLAY_DEVELOPMENT_KEYSTORE = Join-Path $toolchain.signingDirectory 'io.github.sodarx.biliplay.development.jks'
if (-not (Test-Path -LiteralPath $env:BILIPLAY_DEVELOPMENT_KEYSTORE)) {
    & (Join-Path $env:JAVA_HOME 'bin/keytool.exe') -genkeypair -keystore $env:BILIPLAY_DEVELOPMENT_KEYSTORE -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Bili Play Android Development'
    if ($LASTEXITCODE -ne 0) { throw '开发签名生成失败。' }
}
Push-Location $androidRoot
try {
    & (Join-Path $PSScriptRoot 'Check-Privacy.ps1')
    if ($Deliver) { $VerifyRelease = $true }
    $gradleTasks = @(':core:test', ':app:assembleDevelopmentDebug', ':app:lintDevelopmentDebug')
    if ($VerifyRelease) { $gradleTasks += @(':app:assembleDevelopmentRelease', ':app:lintDevelopmentRelease') }
    & $toolchain.gradle --no-daemon @gradleTasks -PDevelopmentBuild=true
    if ($LASTEXITCODE -ne 0) { throw '安卓构建或验证未通过。' }
    foreach ($report in Get-ChildItem -LiteralPath (Join-Path $androidRoot 'app/build/reports') -Filter 'lint-results-*.sarif' -ErrorAction SilentlyContinue) {
        $sarif = Get-Content -LiteralPath $report.FullName -Raw | ConvertFrom-Json
        foreach ($group in @($sarif.runs.results | Where-Object { $_.level -in @('warning','error') } | Group-Object ruleId)) {
            Write-Output ('LINT ' + $report.BaseName + ': ' + $group.Name + '=' + $group.Count)
        }
    }
    $testCount = 0
    foreach ($report in Get-ChildItem -LiteralPath (Join-Path $androidRoot 'core/build/test-results/test') -Filter 'TEST-*.xml') {
        [xml]$suite = Get-Content -LiteralPath $report.FullName -Raw
        $testCount += [int]$suite.testsuite.tests
    }
    $apk = Join-Path $androidRoot 'app/build/outputs/apk/development/debug/app-development-debug.apk'
    $aapt = Join-Path $toolchain.sdk 'build-tools/36.0.0/aapt.exe'
    $badging = & $aapt dump badging $apk
    if ($LASTEXITCODE -ne 0 -or -not ($badging -match "package: name='io.github.sodarx.biliplay.development'")) { throw '开发包身份验证失败。' }
    if (-not ($badging -match "application-label:'Bili Play'")) { throw '应用显示名称验证失败。' }
    if ($VerifyRelease) {
        $releaseApk = Join-Path $androidRoot 'app/build/outputs/apk/development/release/app-development-release-unsigned.apk'
        $releaseBadging = & $aapt dump badging $releaseApk
        if ($LASTEXITCODE -ne 0 -or -not ($releaseBadging -match "package: name='io.github.sodarx.biliplay.development'")) { throw '压缩构建身份验证失败。' }
        Write-Output 'VERIFIED R8 development release, no production package signing or installation'
    }
    & (Join-Path $PSScriptRoot 'Check-Privacy.ps1') -Apk $apk
    if ($Deliver) {
        $version = ((Get-Content -LiteralPath (Join-Path $androidRoot 'version.properties') | Where-Object { $_ -like 'versionName=*' }) -split '=',2)[1]
        if ($version -notmatch '^\d+\.\d+\.\d+$') { throw '版本号格式无效。' }
        $desktop = [Environment]::GetFolderPath('Desktop')
        if (-not (Test-Path -LiteralPath $desktop -PathType Container)) { throw '桌面目录不可用。' }
        $delivery = Join-Path $desktop "Bili_Play_Android_$($version).APK"
        if (Test-Path -LiteralPath $delivery) { throw '目标APK已存在，停止以免覆盖用户文件。' }
        $signed = Join-Path $androidRoot "app/build/outputs/apk/development/release/Bili_Play_Android_$($version).APK"
        $apksigner = Join-Path $toolchain.sdk 'build-tools/36.0.0/apksigner.bat'
        & $apksigner sign --ks $env:BILIPLAY_DEVELOPMENT_KEYSTORE --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $signed $releaseApk
        if ($LASTEXITCODE -ne 0) { throw 'APK 签名失败。' }
        & $apksigner verify $signed
        if ($LASTEXITCODE -ne 0) { throw 'APK 签名校验失败。' }
        & (Join-Path $PSScriptRoot 'Check-Privacy.ps1') -Apk $signed
        [IO.File]::Copy($signed, $delivery, $false)
        & (Join-Path $PSScriptRoot 'Check-Privacy.ps1') -Apk $delivery
        Write-Output ('DELIVERED signed development acceptance APK: ' + $delivery)
    }
    Write-Output ('VERIFIED Android offline tests=' + $testCount + ', development package identity and label correct')
} finally { Pop-Location; & (Join-Path $PSScriptRoot 'Clean-Generated.ps1') -IncludePublishOutput; & (Join-Path $PSScriptRoot 'Check-Privacy.ps1') }
