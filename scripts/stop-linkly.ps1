Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'start-linkly.ps1')

function Stop-Linkly {
    param([string]$ProjectDirectory)
    $ProjectDirectory = [IO.Path]::GetFullPath($ProjectDirectory)
    $lockPath = Join-Path $ProjectDirectory '.linkly-setup.lock'
    try { $setupLock = [IO.File]::Open($lockPath, 'OpenOrCreate', 'ReadWrite', 'None') }
    catch { throw 'Linkly startup or shutdown is already in progress. Wait for it to finish, then rerun stop-linkly.cmd.' }
    try {
        $docker = Get-Command docker -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -eq $docker) { throw 'Docker was not found. Install Docker Desktop to manage this stack.' }
        $script:LinklyDockerCommand = $docker.Source
        if ((Invoke-LinklyDockerQuery @('info', '--format', '{{.ServerVersion}}')).ExitCode -ne 0) {
            throw 'Could not contact Docker. Check Docker Desktop, then rerun stop-linkly.cmd.'
        }
        $projectName = Get-LinklyProjectName $ProjectDirectory
        $containers = @{}
        foreach ($service in @('app', 'postgres', 'redis')) {
            # Labels identify this installation; fixed container names could belong
            # to another project. No secrets or Compose interpolation are needed.
            $query = Invoke-LinklyDockerQuery @('ps', '--filter', "label=com.docker.compose.project=$projectName",
                '--filter', "label=com.docker.compose.service=$service", '--format', '{{.ID}}')
            if ($query.ExitCode -ne 0) { throw 'Could not identify the Linkly containers. Nothing was stopped.' }
            $ids = @($query.Output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            foreach ($id in $ids) {
                if ($id -notmatch '^[a-f0-9]{12,64}$') { throw 'Docker returned an unexpected container ID. Nothing was stopped.' }
            }
            $containers[$service] = $ids
        }
        if (@($containers.Values | ForEach-Object { $_ }).Count -eq 0) {
            Write-Host 'Linkly is already stopped. Database data and .env are preserved.'
            return
        }
        # Stop the app before its dependencies so it can finish in-flight requests.
        # A graceful stop leaves containers and volumes available for the next run.
        foreach ($service in @('app', 'postgres', 'redis')) {
            if ($containers[$service].Count -eq 0) { continue }
            Write-Host "Stopping Linkly $service..."
            $result = Invoke-LinklyDockerQuery (@('stop', '--time', '30') + $containers[$service])
            if ($result.ExitCode -ne 0) {
                throw "Could not stop Linkly $service. Check Docker Desktop and rerun stop-linkly.cmd."
            }
        }
        Write-Host 'Linkly, PostgreSQL and Redis are stopped. Database data and .env are preserved.'
    } finally {
        $setupLock.Dispose()
        Remove-Item -LiteralPath $lockPath -Force -ErrorAction SilentlyContinue
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    try { Stop-Linkly -ProjectDirectory (Split-Path $PSScriptRoot -Parent) }
    catch { Write-Host ("Shutdown failed: " + $_.Exception.Message) -ForegroundColor Red; exit 1 }
}
