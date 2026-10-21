$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    conda run --no-capture-output -n cvte python -m hotspot.cli compare @args
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
