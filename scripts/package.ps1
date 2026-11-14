$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$workspace = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputDirectory = Join-Path $workspace 'dist'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$output = Join-Path $outputDirectory 'astrocare-backend-v1.zip'
$stream = [System.IO.File]::Open($output, [System.IO.FileMode]::Create)
$zip = New-Object System.IO.Compression.ZipArchive($stream, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    $rootFiles = @('package.json','package-lock.json','.env.example','.gitignore','README.md','TEST-RESULTS.md','example-sync.json')
    $files = @($rootFiles | ForEach-Object { Get-Item -LiteralPath (Join-Path $workspace $_) })
    foreach ($directory in @('src','scripts','test','contracts','docs')) {
        $files += @(Get-ChildItem -LiteralPath (Join-Path $workspace $directory) -File -Recurse)
    }
    foreach ($file in $files) {
        if (-not $file.FullName.StartsWith($workspace + '\', [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Package file outside workspace' }
        $relative = $file.FullName.Substring($workspace.Length + 1).Replace('\','/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, ('astrocare-backend/' + $relative), [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally {
    $zip.Dispose()
    $stream.Dispose()
}
Write-Output $output
