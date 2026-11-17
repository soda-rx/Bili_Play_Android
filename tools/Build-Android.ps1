param([Parameter(Mandatory)][string]$ToolchainFile, [switch]$VerifyRelease, [switch]$Deliver, [string]$DevelopmentKeystore)
$ErrorActionPreference = 'Stop'
$androidRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
& python -B (Join-Path $PSScriptRoot 'check_release.py')
if ($LASTEXITCODE -ne 0) { throw '版本预检未通过。' }
$toolchain = Get-Content -LiteralPath $ToolchainFile -Raw | ConvertFrom-Json
$env:JAVA_HOME = $toolchain.javaHome
$env:ANDROID_HOME = $toolchain.sdk
$env:GRADLE_USER_HOME = $toolchain.cache
$env:ANDROID_PREFS_ROOT = Join-Path $toolchain.cache 'android-preferences'
$env:ANDROID_USER_HOME = Join-Path $env:ANDROID_PREFS_ROOT '.android'
New-Item -ItemType Directory -Path $env:ANDROID_USER_HOME -Force | Out-Null
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
New-Item -ItemType Directory -Path $toolchain.signingDirectory -Force | Out-Null
$env:BILIPLAY_DEVELOPMENT_KEYSTORE = if ($DevelopmentKeystore) { [IO.Path]::GetFullPath($DevelopmentKeystore) } else { Join-Path $toolchain.signingDirectory 'io.github.sodarx.biliplay.development.jks' }
if ([IO.Path]::GetFullPath($env:BILIPLAY_DEVELOPMENT_KEYSTORE).StartsWith($androidRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw '签名私钥必须保存在安卓工作区之外。'
}
if (-not (Test-Path -LiteralPath $env:BILIPLAY_DEVELOPMENT_KEYSTORE)) {
    if ($Deliver -or $DevelopmentKeystore) { throw '未找到既有签名密钥，停止交付；不会为覆盖升级自动生成新签名。' }
    & (Join-Path $env:JAVA_HOME 'bin/keytool.exe') -genkeypair -keystore $env:BILIPLAY_DEVELOPMENT_KEYSTORE -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Bili Play Android Development'
    if ($LASTEXITCODE -ne 0) { throw '开发签名生成失败。' }
}
if ($Deliver) {
    $policy = Get-Content -LiteralPath (Join-Path $androidRoot 'release-policy.json') -Raw | ConvertFrom-Json
    $expectedSigner = $policy.developmentSignerSha256
    if ($expectedSigner -notmatch '^[0-9a-fA-F]{64}$') { throw '未配置可信的旧版签名指纹，停止交付。' }
    # Export only the public certificate into memory; never copy or print the private key.
    $publicCertificate = & (Join-Path $env:JAVA_HOME 'bin/keytool.exe') -exportcert -rfc -keystore $env:BILIPLAY_DEVELOPMENT_KEYSTORE -storepass android -alias androiddebugkey 2>$null
    if ($LASTEXITCODE -ne 0) { throw '无法读取签名证书，请核对既有开发密钥；未输出密钥内容。' }
    $certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::CreateFromPem(($publicCertificate -join "`n"))
    try { $actualSigner = $certificate.GetCertHashString([Security.Cryptography.HashAlgorithmName]::SHA256) }
    finally { $certificate.Dispose(); $publicCertificate = $null }
    if ($actualSigner -ne $expectedSigner) { throw '签名与旧版安装包不匹配，停止交付；需要原始签名密钥。' }
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
        $desktop = [Environment]::GetFolderPath('Desktop')
        if (-not (Test-Path -LiteralPath $desktop -PathType Container)) { throw '桌面目录不可用。' }
        $delivery = Join-Path $desktop "Bili_Play_Android_$($version).apk"
        if (Test-Path -LiteralPath $delivery) { throw '目标APK已存在，停止以免覆盖用户文件。' }
        $signed = Join-Path $androidRoot "app/build/outputs/apk/development/release/Bili_Play_Android_$($version).apk"
        $apksigner = Join-Path $toolchain.sdk 'build-tools/36.0.0/apksigner.bat'
        & $apksigner sign --ks $env:BILIPLAY_DEVELOPMENT_KEYSTORE --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $signed $releaseApk
        if ($LASTEXITCODE -ne 0) { throw 'APK 签名失败。' }
        $signerReport = & $apksigner verify --print-certs $signed
        if ($LASTEXITCODE -ne 0) { throw 'APK 签名校验失败。' }
        $signerMatches = [regex]::Matches(($signerReport -join "`n"), '(?im)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-f]{64})\s*$')
        if ($signerMatches.Count -ne 1 -or $signerMatches[0].Groups[1].Value -ne $expectedSigner) { throw '最终 APK 签名与旧版不匹配，停止交付。' }
        & (Join-Path $PSScriptRoot 'Check-Privacy.ps1') -Apk $signed
        [IO.File]::Copy($signed, $delivery, $false)
        & (Join-Path $PSScriptRoot 'Check-Privacy.ps1') -Apk $delivery
        Write-Output ('DELIVERED signed development acceptance APK: ' + $delivery)
    }
    Write-Output ('VERIFIED Android offline tests=' + $testCount + ', development package identity and label correct')
} finally { Pop-Location; & (Join-Path $PSScriptRoot 'Clean-Generated.ps1') -IncludePublishOutput; & (Join-Path $PSScriptRoot 'Check-Privacy.ps1') }
