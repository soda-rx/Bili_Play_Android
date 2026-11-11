param([string]$Apk)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$findings = [Collections.Generic.List[string]]::new()
$textExtensions = @('.kt','.kts','.ps1','.md','.txt','.json','.xml','.properties','.yml','.yaml','.cmd','.bat')
$privatePattern = '-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[A-Z0-9]{16}'
$cookiePattern = '(?i)(?:SESSDATA|bili_jct)[ ]*[=:][ ]*([A-Za-z0-9_%+./-]{8,})'
function Check-Text([string]$name, [string]$body, [bool]$syntheticTest) {
    if ($body -match $privatePattern) { $findings.Add("private key/token: $name") }
    foreach ($match in [regex]::Matches($body,$cookiePattern)) {
        if (-not ($syntheticTest -and $match.Groups[1].Value -match '^(?:late_)?synthetic(?:_csrf)?$')) { $findings.Add("literal session credential: $name") }
    }
    if ($body -match '(?i)[A-Z]:[\\/]+Users[\\/]+[^\\/\s"'']+[\\/]+') { $findings.Add("personal absolute path: $name") }
}
function Scan-Directory([string]$path) {
    foreach ($item in Get-ChildItem -LiteralPath $path -Force) {
        $relative = [IO.Path]::GetRelativePath($root,$item.FullName)
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { $findings.Add("uninspected link: $relative"); continue }
        if ($item.PSIsContainer) {
            if ($item.Name -in @('.git','.gradle','.kotlin','build')) { continue }
            Scan-Directory $item.FullName
        } else {
            if ($item.Extension -in @('.jks','.keystore','.pfx','.p12','.key','.pem','.db','.sqlite','.sqlite3','.hprof','.trace','.dump','.heapdump','.log','.apk','.aab') -or $item.Name -match '^(?:\.env(?:\..*)?|local\.properties|cookies\..*|session\..*)$' -or $item.Name -match '\.(?:db|sqlite3?)(?:-wal|-shm|-journal)$') { $findings.Add("local/private/generated file: $relative") }
            if ($item.Extension -in $textExtensions -or $item.Name -eq 'config') { Check-Text $relative ([IO.File]::ReadAllText($item.FullName)) ($relative -match '^core[\\/]src[\\/]test[\\/]') }
        }
    }
}
Scan-Directory $root
$gitConfig = Join-Path $root '.git/config'
if (Test-Path -LiteralPath $gitConfig) {
    $gitText = [IO.File]::ReadAllText($gitConfig)
    if ($gitText -match 'https?://[^\s/@]+:[^\s/@]+@') { $findings.Add('embedded remote credential: .git/config') }
}
if ($Apk) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($Apk))
    try { foreach ($entry in $zip.Entries) {
        if ($entry.FullName -match '(?i)(?:^|/)(?:\.git|profiles|databases|shared_prefs|cookies)(?:/|\.)|\.(?:jks|keystore|pfx|p12|key|pem|db|sqlite3?|log|hprof|trace|dump|heapdump)(?:$|-)') { $findings.Add("private APK entry: $($entry.FullName)") }
        if ([IO.Path]::GetExtension($entry.FullName) -in @('.txt','.json','.properties','.html','.js')) {
            $reader = [IO.StreamReader]::new($entry.Open()); try { Check-Text ("APK/"+$entry.FullName) $reader.ReadToEnd() $false } finally { $reader.Dispose() }
        }
    } } finally { $zip.Dispose() }
}
if ($findings.Count) { $findings | Sort-Object -Unique | ForEach-Object { Write-Output $_ }; throw '隐私检查发现需审查的文件；未输出敏感值。' }
Write-Output 'PRIVACY CHECK PASSED: workspace and optional APK; no sensitive values printed'
