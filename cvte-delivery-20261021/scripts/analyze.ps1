$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $repoRoot
try {
    & conda run --no-capture-output -n cvte python -m hotspot.cli analyze @args
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
