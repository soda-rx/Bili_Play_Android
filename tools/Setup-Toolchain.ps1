param([string]$ToolchainRoot = (Join-Path $env:LOCALAPPDATA 'BiliPlay.Android.Toolchain'))
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$toolchainRoot = [IO.Path]::GetFullPath($ToolchainRoot)
$androidWorkspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($toolchainRoot -eq $androidWorkspaceRoot -or $toolchainRoot.StartsWith($androidWorkspaceRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
    throw '工具链、缓存和签名目录必须放在安卓工作区之外。'
}
New-Item -ItemType Directory -Path $toolchainRoot -Force | Out-Null
function Get-XmlBody([string]$Uri) {
    $body = (Invoke-WebRequest -Uri $Uri).Content
    if ($body -is [byte[]]) { $body = [Text.Encoding]::UTF8.GetString($body) }
    return [xml]$body
}
function Get-VerifiedArchive([string]$Url,[string]$Destination,[string]$Hash,[string]$Algorithm) {
    if (-not (Test-Path -LiteralPath $Destination)) { Invoke-WebRequest -Uri $Url -OutFile $Destination }
    if ((Get-FileHash -LiteralPath $Destination -Algorithm $Algorithm).Hash -ne $Hash) { throw '工具链下载校验失败。' }
}
$jdk = @(Get-ChildItem -LiteralPath $toolchainRoot -Directory -Filter 'jdk-*' | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe') }) | Select-Object -First 1
if (-not $jdk) {
    Write-Output 'Downloading isolated JDK 21...'
    $assets = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $package = $assets[0].binary.package
    $archive = Join-Path $toolchainRoot 'jdk.zip'
    Get-VerifiedArchive $package.link $archive $package.checksum 'SHA256'
    Expand-Archive -LiteralPath $archive -DestinationPath $toolchainRoot -Force
    $jdk = Get-ChildItem -LiteralPath $toolchainRoot -Directory -Filter 'jdk-*' | Select-Object -First 1
}
$env:JAVA_HOME = $jdk.FullName
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
$gradleVersion = '9.6.0'
$gradle = Join-Path $toolchainRoot "gradle-$gradleVersion"
if (-not (Test-Path -LiteralPath (Join-Path $gradle 'bin\gradle.bat'))) {
    Write-Output 'Downloading verified Gradle...'
    $url = "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip"
    $hashResponse = (Invoke-WebRequest ($url + '.sha256')).Content
    if ($hashResponse -is [byte[]]) { $hashResponse = [Text.Encoding]::UTF8.GetString($hashResponse) }
    Get-VerifiedArchive $url (Join-Path $toolchainRoot 'gradle.zip') ([string]$hashResponse).Trim() 'SHA256'
    Expand-Archive -LiteralPath (Join-Path $toolchainRoot 'gradle.zip') -DestinationPath $toolchainRoot -Force
}
$sdk = Join-Path $toolchainRoot 'sdk-classic'
$env:ANDROID_USER_HOME = Join-Path $toolchainRoot 'android-user-home'
New-Item -ItemType Directory -Path $env:ANDROID_USER_HOME -Force | Out-Null
$manager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $manager)) {
    Write-Output 'Downloading verified Android SDK command-line tools...'
    $repository = Get-XmlBody 'https://dl.google.com/android/repository/repository2-3.xml'
    $tools = $repository.SelectSingleNode('//*[local-name()="remotePackage" and @path="cmdline-tools;19.0"]')
    $archiveInfo = ($tools.archives.archive | Where-Object { $_.'host-os' -eq 'windows' }).complete
    $archive = Join-Path $toolchainRoot 'sdk-tools-19.zip'
    Get-VerifiedArchive ('https://dl.google.com/android/repository/' + $archiveInfo.url) $archive $archiveInfo.checksum.InnerText 'SHA1'
    $expanded = Join-Path $toolchainRoot 'sdk-tools-19-expanded'
    Expand-Archive -LiteralPath $archive -DestinationPath $expanded -Force
    New-Item -ItemType Directory -Path (Join-Path $sdk 'cmdline-tools') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $expanded 'cmdline-tools') -Destination (Join-Path $sdk 'cmdline-tools\latest') -Recurse -Force
    # Copy-Item into an existing directory nests the source; correct only this new SDK directory.
    $nested = Join-Path $sdk 'cmdline-tools\latest\cmdline-tools'
    if (Test-Path -LiteralPath $nested) { Get-ChildItem -LiteralPath $nested -Force | Copy-Item -Destination (Join-Path $sdk 'cmdline-tools\latest') -Recurse -Force }
}
if (-not (Test-Path -LiteralPath (Join-Path $sdk 'platforms\android-37.0\android.jar'))) {
    Write-Output 'Preparing isolated Android SDK licenses and API 37...'
    1..100 | ForEach-Object { 'y' } | & $manager "--sdk_root=$sdk" --licenses *> (Join-Path $toolchainRoot 'sdk-licenses.log')
    & $manager "--sdk_root=$sdk" 'platforms;android-37.0' 'build-tools;36.0.0' *> (Join-Path $toolchainRoot 'sdk-install.log')
    if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $toolchainRoot 'sdk-install.log') -Tail 15; throw 'Android SDK setup failed; inspect the isolated SDK install log.' }
}
$paths = @{ javaHome=$env:JAVA_HOME; sdk=$sdk; gradle=(Join-Path $gradle 'bin\gradle.bat'); cache=(Join-Path $toolchainRoot 'gradle-user-home'); signingDirectory=(Join-Path $toolchainRoot 'signing') }
$paths | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $toolchainRoot 'toolchain.json') -Encoding utf8
Write-Output ('TOOLCHAIN_READY ' + (Join-Path $toolchainRoot 'toolchain.json'))
