#requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$ToolchainFile,
    [Parameter(Mandatory)][string]$SigningConfigFile,
    [switch]$Deliver
)
$ErrorActionPreference = 'Stop'
$androidRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$toolchain = Get-Content -LiteralPath $ToolchainFile -Raw | ConvertFrom-Json
$signing = Get-Content -LiteralPath $SigningConfigFile -Raw | ConvertFrom-Json
$policy = Get-Content -LiteralPath (Join-Path $androidRoot 'release-policy.json') -Raw | ConvertFrom-Json
if ($policy.productionPackageId -ne 'io.github.sodarx.biliplay' -or $signing.packageId -ne $policy.productionPackageId) { throw '正式包身份配置不匹配。' }
if ($policy.productionSignerSha256 -notmatch '^[a-fA-F0-9]{64}$' -or $signing.certificateSha256 -ne $policy.productionSignerSha256 -or $signing.alias -ne 'biliplay-production') { throw '正式签名配置与固定指纹不匹配。' }
foreach ($path in @($SigningConfigFile,$signing.keyStore,$signing.credentialFile,$signing.certificateFile)) {
    $full = [IO.Path]::GetFullPath($path)
    if ($full.StartsWith($androidRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or -not (Test-Path -LiteralPath $full -PathType Leaf)) { throw '正式签名资料必须在工程外且完整存在。' }
    $ancestor = $full
    while ($ancestor) {
        if ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '正式签名资料包含链接。' }
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
    }
}
$env:JAVA_HOME = $toolchain.javaHome
$env:ANDROID_HOME = $toolchain.sdk
$env:GRADLE_USER_HOME = $toolchain.cache
$env:ANDROID_PREFS_ROOT = Join-Path $toolchain.cache 'android-preferences'
$env:ANDROID_USER_HOME = Join-Path $env:ANDROID_PREFS_ROOT '.android'
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
$keytool = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
$aapt = Join-Path $toolchain.sdk 'build-tools/36.0.0/aapt.exe'
$apksigner = Join-Path $toolchain.sdk 'build-tools/36.0.0/apksigner.bat'
$zipalign = Join-Path $toolchain.sdk 'build-tools/36.0.0/zipalign.exe'
$previousPassword = $env:BILIPLAY_PRODUCTION_PASSWORD
$env:BILIPLAY_PRODUCTION_PASSWORD = $null
function Invoke-WithProductionPassword([scriptblock]$Action) {
    $entropy = [Text.Encoding]::UTF8.GetBytes('BiliPlay.Android.ProductionSigning.v1')
    try {
        $plain = [Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($signing.credentialFile),$entropy,[Security.Cryptography.DataProtectionScope]::CurrentUser)
        $env:BILIPLAY_PRODUCTION_PASSWORD = [Text.Encoding]::UTF8.GetString($plain)
        & $Action
    } finally {
        $env:BILIPLAY_PRODUCTION_PASSWORD = $null
        if ($plain) { [Array]::Clear($plain,0,$plain.Length) }
    }
}
Push-Location $androidRoot
try {
    & python -B (Join-Path $PSScriptRoot 'check_release.py')
    if ($LASTEXITCODE -ne 0) { throw '正式版本预检失败。' }
    Invoke-WithProductionPassword {
        $pem = & $keytool -exportcert -rfc -keystore $signing.keyStore -storepass:env BILIPLAY_PRODUCTION_PASSWORD -alias $signing.alias 2>$null
        if ($LASTEXITCODE -ne 0) { throw '正式密钥无法读取；不会重新生成或更换密钥。' }
        $cert = [Security.Cryptography.X509Certificates.X509Certificate2]::CreateFromPem(($pem -join "`n"))
        try { if ($cert.GetCertHashString([Security.Cryptography.HashAlgorithmName]::SHA256) -ne $policy.productionSignerSha256) { throw '正式密钥实际指纹与固定指纹不匹配。' } } finally { $cert.Dispose(); $pem=$null }
    }
    & (Join-Path $PSScriptRoot 'Build-Android.ps1') -ToolchainFile $ToolchainFile -VerifyRelease
    & (Join-Path $PSScriptRoot 'Check-Privacy.ps1')
    & $toolchain.gradle --no-daemon :app:assembleProductionRelease :app:lintProductionRelease -PDevelopmentBuild=false
    if ($LASTEXITCODE -ne 0) { throw '正式 Release 构建或 Lint 失败。' }
    foreach ($report in Get-ChildItem -LiteralPath (Join-Path $androidRoot 'app/build/reports') -Filter 'lint-results-productionRelease.sarif') {
        $sarif = Get-Content -LiteralPath $report.FullName -Raw | ConvertFrom-Json
        foreach ($group in @($sarif.runs.results | Where-Object { $_.level -in @('warning','error') } | Group-Object ruleId)) { Write-Output ('LINT productionRelease: ' + $group.Name + '=' + $group.Count) }
    }
    $versionLines = Get-Content -LiteralPath (Join-Path $androidRoot 'version.properties')
    $version = (($versionLines | Where-Object { $_ -like 'versionName=*' }) -split '=',2)[1]
    $code = (($versionLines | Where-Object { $_ -like 'versionCode=*' }) -split '=',2)[1]
    $unsigned = Join-Path $androidRoot 'app/build/outputs/apk/production/release/app-production-release-unsigned.apk'
    $badging = & $aapt dump badging $unsigned
    $expected = "package: name='io.github.sodarx.biliplay' versionCode='$code' versionName='$version'"
    if ($LASTEXITCODE -ne 0 -or -not ($badging -like "$expected*")) { throw '正式 APK 内部身份或版本不匹配。' }
    if (-not ($badging -match "application-label:'Bili Play'")) { throw '正式应用显示名不匹配。' }
    $manifest = & $aapt dump xmltree $unsigned AndroidManifest.xml
    if ($LASTEXITCODE -ne 0 -or ($manifest -match 'android:(?:debuggable|testOnly).*\(type 0x12\)0xffffffff') -or -not ($manifest -match 'android:allowBackup.*\(type 0x12\)0x0')) { throw '正式包调试/测试/备份标记不符合要求。' }
    if ($manifest -match 'io\.github\.sodarx\.biliplay\.development') { throw '正式清单包含开发安装身份。' }
    $buildConfig = Get-Content -LiteralPath (Join-Path $androidRoot 'app/build/generated/source/buildConfig/production/release/io/github/sodarx/biliplay/BuildConfig.java') -Raw
    if ($buildConfig -notmatch 'DEVELOPMENT_BUILD\s*=\s*false' -or $buildConfig -notmatch 'DEBUG\s*=\s*false') { throw '正式构建模式标记不正确。' }
    $aligned = Join-Path $androidRoot 'app/build/outputs/apk/production/release/aligned.apk'
    & $zipalign -P 16 -f 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw '正式 APK 对齐失败。' }
    $signed = Join-Path $androidRoot "app/build/outputs/apk/production/release/Bili_Play_Android_$version.apk"
    Invoke-WithProductionPassword {
        & $apksigner sign --ks $signing.keyStore --ks-key-alias $signing.alias --ks-pass env:BILIPLAY_PRODUCTION_PASSWORD --key-pass env:BILIPLAY_PRODUCTION_PASSWORD --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out $signed $aligned
        if ($LASTEXITCODE -ne 0) { throw '正式 APK 签名失败；未输出凭据。' }
    }
    $signature = & $apksigner verify --verbose --print-certs $signed
    if ($LASTEXITCODE -ne 0) { throw '正式 APK 签名验证失败。' }
    $signerMatches = [regex]::Matches(($signature -join "`n"),'(?im)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-f]{64})\s*$')
    if ($signerMatches.Count -ne 1 -or $signerMatches[0].Groups[1].Value -ne $policy.productionSignerSha256) { throw '正式 APK 实际签名与固定指纹不一致。' }
    & $zipalign -c -P 16 4 $signed
    if ($LASTEXITCODE -ne 0) { throw '已签名 APK 对齐验证失败。' }
    & (Join-Path $PSScriptRoot 'Check-Privacy.ps1') -Apk $signed
    if ($Deliver) {
        $desktop = [Environment]::GetFolderPath('Desktop')
        $destination = Join-Path $desktop "Bili_Play_Android_$version.apk"
        if (Test-Path -LiteralPath $destination) { throw '桌面目标已存在，停止以免覆盖用户文件。' }
        [IO.File]::Copy($signed,$destination,$false)
        & (Join-Path $PSScriptRoot 'Check-Privacy.ps1') -Apk $destination
        Write-Output ('DELIVERED production APK: ' + $destination)
    }
    Write-Output ('VERIFIED production identity=io.github.sodarx.biliplay version=' + $version + ' code=' + $code)
    Write-Output ('VERIFIED production certificate SHA256=' + $policy.productionSignerSha256)
} finally {
    $env:BILIPLAY_PRODUCTION_PASSWORD = $previousPassword
    Pop-Location
    & (Join-Path $PSScriptRoot 'Clean-Generated.ps1') -IncludePublishOutput
    & (Join-Path $PSScriptRoot 'Check-Privacy.ps1')
}
