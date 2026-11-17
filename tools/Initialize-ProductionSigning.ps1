#requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$ToolchainFile,
    [string]$SigningRoot = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.biliplay_android_signing/production')
)
$ErrorActionPreference = 'Stop'
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$SigningRoot = [IO.Path]::GetFullPath($SigningRoot)
foreach ($directory in @($SigningRoot)) {
    if ($directory -eq $workspaceRoot -or $directory.StartsWith($workspaceRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw '正式密钥和恢复资料必须保存在工程外。' }
    $ancestor = $directory
    while ($ancestor) {
        if ((Test-Path -LiteralPath $ancestor) -and ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw '签名目录包含链接，停止初始化。' }
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
    }
}
$configPath = Join-Path $SigningRoot 'production.json'
if ((Test-Path -LiteralPath $SigningRoot) -and @(Get-ChildItem -LiteralPath $SigningRoot -Force).Count) { throw '正式签名目录已有文件；禁止自动覆盖或重新生成密钥。' }
foreach ($directory in @($SigningRoot)) {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true,$false)
    $user = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.SetOwner($user)
    foreach ($sid in @($user,[Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit, ObjectInherit','None','Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $directory -AclObject $acl
}
$toolchain = Get-Content -LiteralPath $ToolchainFile -Raw | ConvertFrom-Json
$keytool = Join-Path $toolchain.javaHome 'bin/keytool.exe'
$key = Join-Path $SigningRoot 'production.p12'
$public = Join-Path $SigningRoot 'production.cer'
$credential = Join-Path $SigningRoot 'password.dpapi'
$entropy = [Text.Encoding]::UTF8.GetBytes('BiliPlay.Android.ProductionSigning.v1')
$random = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
$password = [Convert]::ToBase64String($random)
$passwordBytes = [Text.Encoding]::UTF8.GetBytes($password)
$previousPassword = $env:BILIPLAY_PRODUCTION_PASSWORD
try {
    $env:BILIPLAY_PRODUCTION_PASSWORD = $password
    $output = & $keytool -genkeypair -storetype PKCS12 -keystore $key -storepass:env BILIPLAY_PRODUCTION_PASSWORD -alias biliplay-production -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10000 -dname 'CN=Bili Play Android Production, O=soda-rx' 2>&1
    if ($LASTEXITCODE -ne 0) { throw '正式密钥初始化失败；未输出敏感信息。' }
    $output = & $keytool -exportcert -keystore $key -storepass:env BILIPLAY_PRODUCTION_PASSWORD -alias biliplay-production -file $public 2>&1
    if ($LASTEXITCODE -ne 0) { throw '公开证书导出失败。' }
    $certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new([IO.File]::ReadAllBytes($public))
    try { $fingerprint = $certificate.GetCertHashString([Security.Cryptography.HashAlgorithmName]::SHA256).ToLowerInvariant() } finally { $certificate.Dispose() }
    $protected = [Security.Cryptography.ProtectedData]::Protect($passwordBytes,$entropy,[Security.Cryptography.DataProtectionScope]::CurrentUser)
    [IO.File]::WriteAllBytes($credential,$protected)
    $recoveryPassword = Join-Path $SigningRoot 'recovery-password.txt'
    [IO.File]::WriteAllText($recoveryPassword,$password,[Text.Encoding]::UTF8)
    if ([IO.File]::ReadAllText($recoveryPassword) -cne $password) { throw '恢复密码写入校验失败。' }
    @{ keyStore=$key; alias='biliplay-production'; credentialFile=$credential; certificateFile=$public;
       certificateSha256=$fingerprint; packageId='io.github.sodarx.biliplay'; recoveryPasswordFile=$recoveryPassword } |
        ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding utf8
    Write-Output ('PRODUCTION_SIGNING_CONFIG ' + $configPath)
    Write-Output ('PUBLIC_CERTIFICATE_SHA256 ' + $fingerprint)
    Write-Output 'Signing files and recovery password saved in one private directory; no secrets printed. Keep an encrypted offline backup.'
} finally {
    $env:BILIPLAY_PRODUCTION_PASSWORD = $previousPassword
    [Array]::Clear($random,0,$random.Length)
    [Array]::Clear($passwordBytes,0,$passwordBytes.Length)
    $password=$null; $output=$null
}
