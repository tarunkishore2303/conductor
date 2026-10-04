# Windows helper for standalone Docker Compose connected to Podman.
# Usage: .\scripts\compose.ps1 up --build -d
$ErrorActionPreference = 'Stop'
$podmanCommand = Get-Command podman -ErrorAction SilentlyContinue
if (-not $podmanCommand) {
    $podmanPath = Join-Path $env:ProgramFiles 'RedHat\Podman'
    if (-not (Test-Path (Join-Path $podmanPath 'podman.exe'))) {
        throw 'Install Podman first: winget install --id RedHat.Podman --exact'
    }
    $env:PATH = "$podmanPath;$env:PATH"
}
$composeCommand = Get-Command docker-compose -ErrorAction SilentlyContinue
if ($composeCommand) {
    $env:PODMAN_COMPOSE_PROVIDER = $composeCommand.Source
} else {
    $composeLink = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Links\docker-compose.exe'
    if (-not (Test-Path $composeLink)) {
        $composePackages = @(Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Docker.DockerCompose*\docker-compose*.exe" -ErrorAction SilentlyContinue)
        if ($composePackages.Count -eq 0) {
            throw 'Install standalone Docker Compose: winget install --id Docker.DockerCompose --exact'
        }
        $composeLink = ($composePackages | Sort-Object FullName -Descending | Select-Object -First 1).FullName
    }
    $env:PODMAN_COMPOSE_PROVIDER = $composeLink
}
& podman compose -f (Join-Path $PSScriptRoot '..\compose.yml') @args
exit $LASTEXITCODE
