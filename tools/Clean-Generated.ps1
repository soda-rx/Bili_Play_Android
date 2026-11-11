param([switch]$IncludePublishOutput)
$ErrorActionPreference = 'Stop'
$androidRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
foreach ($marker in @('settings.gradle.kts','app/build.gradle.kts','core/build.gradle.kts')) {
    if (-not (Test-Path -LiteralPath (Join-Path $androidRoot $marker) -PathType Leaf)) { throw '安卓工程标记不完整。' }
}
$relativePaths = @('app/build','core/build','build','artifacts','.gradle','.kotlin')
if ($IncludePublishOutput) { $relativePaths += 'dist' }
$targets = @()
foreach ($relative in $relativePaths) {
    $full = [IO.Path]::GetFullPath((Join-Path $androidRoot $relative))
    if (-not $full.StartsWith($androidRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw '目标超出安卓工程。' }
    $ancestor = $full
    while ($ancestor -and $ancestor.StartsWith($androidRoot,[StringComparison]::OrdinalIgnoreCase)) {
        if ((Test-Path -LiteralPath $ancestor) -and ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw '目标或父目录包含链接，停止清理。' }
        if ($ancestor -eq $androidRoot) { break }; $ancestor = [IO.Path]::GetDirectoryName($ancestor)
    }
    if (-not (Test-Path -LiteralPath $full)) { continue }
    if (-not (Get-Item -LiteralPath $full).PSIsContainer) { throw '目标不是目录。' }
    if (@(Get-ChildItem -LiteralPath $full -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) { throw '生成目录包含链接。' }
    $targets += $full
}
foreach ($process in Get-Process -ErrorAction SilentlyContinue) {
    try { $executable = $process.Path } catch { continue }
    if ($executable) { foreach ($target in $targets) { if ($executable.StartsWith($target + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw '生成目录中存在运行中的程序。' } } }
}
foreach ($target in $targets) { Remove-Item -LiteralPath $target -Recurse -Force }
Write-Output ('CLEAN Android generated directories=' + $targets.Count)
