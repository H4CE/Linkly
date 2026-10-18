param([switch]$FollowLogs)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function New-LinklySecret {
    $bytes = New-Object byte[] 48
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($bytes)
        return [Convert]::ToBase64String($bytes)
    } finally {
        $random.Dispose()
    }
}

function Get-LinklyEnvSetting {
    param([string]$Text, [string]$Name)
    $pattern = '(?m)^[ \t]*(?:export[ \t]+)?' + [regex]::Escape($Name) + '[ \t]*=[^\r\n]*'
    $entries = [regex]::Matches($Text, $pattern)
    if ($entries.Count -gt 1) { throw "Duplicate $Name entries in .env. Keep one entry and rerun setup." }
    $value = ''
    if ($entries.Count -eq 1) {
        $value = ($entries[0].Value -split '=', 2)[1].Trim()
        if ($value -match '^''(.*)''(?:\s+#.*)?$' -or $value -match '^"(.*)"(?:\s+#.*)?$') {
            $value = $Matches[1]
        } else {
            $value = ($value -replace '\s+#.*$', '').Trim()
        }
    }
    return [pscustomobject]@{ Pattern = $pattern; Found = ($entries.Count -eq 1); Value = $value }
}

function Test-LinklyMissingValue {
    param([string]$Value)
    return [string]::IsNullOrWhiteSpace($Value) -or $Value -like 'replace-with-*' -or
        $Value -match '^your-(database-password|generated-jwt-secret|generated-verification-secret)$' -or
        $Value -match '^YOUR_(POSTGRES_PASSWORD|JWT_SECRET|VERIFICATION_SECRET)_HERE$' -or
        $Value -match '^<[^>]+>$'
}

function Initialize-LinklyEnvironment {
    param([string]$ProjectDirectory, [bool]$ExistingDatabase)
    $envPath = Join-Path $ProjectDirectory '.env'
    $exists = Test-Path -LiteralPath $envPath
    $sourcePath = if ($exists) { $envPath } else { Join-Path $ProjectDirectory '.env.example' }
    $text = [IO.File]::ReadAllText($sourcePath)
    $password = Get-LinklyEnvSetting -Text $text -Name 'DB_PASSWORD'
    if ((Test-LinklyMissingValue $password.Value) -and $ExistingDatabase) {
        throw 'Existing PostgreSQL data was found but DB_PASSWORD is missing. Restore its existing password in .env, then rerun setup. No credentials were changed.'
    }

    $updated = $text
    foreach ($name in @('DB_PASSWORD', 'JWT_SECRET', 'VERIFICATION_SECRET')) {
        $setting = Get-LinklyEnvSetting -Text $updated -Name $name
        if (Test-LinklyMissingValue $setting.Value) {
            $assignment = $name + '=' + (New-LinklySecret)
            if ($setting.Found) {
                $updated = [regex]::Replace($updated, $setting.Pattern, $assignment)
            } else {
                if ($updated.Length -gt 0 -and -not $updated.EndsWith("`n")) { $updated += "`r`n" }
                $updated += $assignment + "`r`n"
            }
        }
    }

    if (-not $exists -or $updated -cne $text) {
        # Replace only after the complete file has been written. Never print generated credentials.
        $temporary = Join-Path $ProjectDirectory ('.env.setup-' + [Guid]::NewGuid().ToString('N') + '.tmp')
        try {
            [IO.File]::WriteAllText($temporary, $updated, (New-Object Text.UTF8Encoding($false)))
            # Windows PowerShell 5.1 converts $null to an empty string for this
            # .NET string argument. NullString passes a real null backup path.
            if ($exists) { [IO.File]::Replace($temporary, $envPath, [System.Management.Automation.Language.NullString]::Value) }
            else { [IO.File]::Move($temporary, $envPath) }
        } finally {
            if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
        }
        Write-Host 'Prepared .env with generated credentials. Existing configured values were preserved.'
    } else {
        Write-Host 'Reusing existing .env credentials.'
    }
}

function Invoke-LinklyDockerQuery {
    param([string[]]$ArgumentList)
    # Windows PowerShell treats native stderr as error records. Docker's normal progress
    # output must not become a terminating PowerShell error; always check its exit code.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $script:LinklyDockerCommand @ArgumentList 2>$null)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    return [pscustomobject]@{ ExitCode = $code; Output = $output }
}

function Wait-LinklyDocker {
    if ((Invoke-LinklyDockerQuery @('info', '--format', '{{.ServerVersion}}')).ExitCode -eq 0) { return }
    $desktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (-not (Test-Path -LiteralPath $desktop)) {
        throw 'Docker is not ready. Start your Docker engine or install Docker Desktop, then rerun setup.'
    }
    Write-Host 'Starting Docker Desktop. This can take a minute...'
    Start-Process -FilePath $desktop -WindowStyle Hidden | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    do {
        Start-Sleep -Seconds 2
        if ((Invoke-LinklyDockerQuery @('info', '--format', '{{.ServerVersion}}')).ExitCode -eq 0) { return }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Docker Desktop did not become ready. Open it, finish any first-run setup, and rerun start-linkly.cmd.'
}

function Get-LinklyProjectName {
    param([string]$ProjectDirectory)
    $name = $env:COMPOSE_PROJECT_NAME
    $envPath = Join-Path $ProjectDirectory '.env'
    if ([string]::IsNullOrWhiteSpace($name) -and (Test-Path -LiteralPath $envPath)) {
        $name = (Get-LinklyEnvSetting -Text ([IO.File]::ReadAllText($envPath)) -Name 'COMPOSE_PROJECT_NAME').Value
    }
    if ([string]::IsNullOrWhiteSpace($name)) {
        $name = ((Split-Path $ProjectDirectory -Leaf).ToLowerInvariant() -replace '[^a-z0-9_-]', '') -replace '^[-_]+', ''
    }
    if ($name -notmatch '^[a-z0-9][a-z0-9_-]*$') { throw 'Use a valid COMPOSE_PROJECT_NAME in .env for this folder.' }
    return $name
}

function Test-LinklyExistingDatabase {
    param([string]$ProjectName)
    $containers = Invoke-LinklyDockerQuery @('ps', '--all', '--filter', "label=com.docker.compose.project=$ProjectName",
        '--filter', 'label=com.docker.compose.service=postgres', '--format', '{{.ID}}')
    $volumes = Invoke-LinklyDockerQuery @('volume', 'ls', '--filter', "label=com.docker.compose.project=$ProjectName",
        '--filter', 'label=com.docker.compose.volume=postgres-data', '--format', '{{.Name}}')
    if ($containers.ExitCode -ne 0 -or $volumes.ExitCode -ne 0) {
        throw 'Could not check existing PostgreSQL data. No credentials were generated; check Docker and retry.'
    }
    return @($containers.Output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count -gt 0 -or
        @($volumes.Output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count -gt 0
}

function Wait-LinklyApplication {
    param([int]$Port)
    Write-Host 'Waiting for Linkly to become healthy...'
    $deadline = [DateTime]::UtcNow.AddSeconds(180)
    do {
        try {
            # Invoke-RestMethod decodes JSON even when Windows PowerShell exposes
            # Invoke-WebRequest.Content as raw bytes for responses without a charset.
            $health = Invoke-RestMethod -UseBasicParsing -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 5
            if ($health.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Linkly did not become healthy. Check docker compose logs app, or resolve a port/credential conflict and rerun setup.'
}

function Show-LinklyLogs {
    param([string]$ProjectName)
    $query = Invoke-LinklyDockerQuery @('ps', '--filter', "label=com.docker.compose.project=$ProjectName",
        '--filter', 'label=com.docker.compose.service=app', '--format', '{{.ID}}')
    if ($query.ExitCode -ne 0) { throw 'Could not identify the running app for live logs. Check Docker Desktop.' }
    $ids = @($query.Output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($ids.Count -eq 0) {
        Write-Host 'Linkly app has stopped.'
        return
    }
    if ($ids.Count -ne 1 -or $ids[0] -notmatch '^[a-f0-9]{12,64}$') {
        throw 'Could not identify a single Linkly app container for live logs.'
    }
    Write-Host 'Showing live application logs. Run stop-linkly.cmd to stop the stack.'
    Write-Host 'Closing this window leaves the Docker containers running.'
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $script:LinklyDockerCommand logs --follow --tail 50 $ids[0]
        $logsExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($logsExitCode -ne 0) { throw 'The live log stream failed. Check Docker Desktop or run docker compose logs app.' }
    Write-Host 'Application log stream ended.'
}

function Start-Linkly {
    param([string]$ProjectDirectory, [switch]$FollowLogs)
    $ProjectDirectory = [IO.Path]::GetFullPath($ProjectDirectory)
    $lockPath = Join-Path $ProjectDirectory '.linkly-setup.lock'
    try { $setupLock = [IO.File]::Open($lockPath, 'OpenOrCreate', 'ReadWrite', 'None') }
    catch { throw 'Linkly startup or shutdown is already in progress. Wait for it to finish, then retry.' }
    try {
        $docker = Get-Command docker -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -eq $docker) { throw 'Docker was not found. Install Docker Desktop, then rerun start-linkly.cmd.' }
        $script:LinklyDockerCommand = $docker.Source
        Wait-LinklyDocker
        if ((Invoke-LinklyDockerQuery @('compose', 'version')).ExitCode -ne 0) { throw 'Docker Compose is required. Update Docker Desktop and rerun setup.' }
        $projectName = Get-LinklyProjectName $ProjectDirectory
        $existingDatabase = Test-LinklyExistingDatabase $projectName
        Initialize-LinklyEnvironment -ProjectDirectory $ProjectDirectory -ExistingDatabase $existingDatabase
        $composeArguments = @('compose', '--project-directory', $ProjectDirectory, '--project-name', $projectName,
            '--env-file', (Join-Path $ProjectDirectory '.env'), '--file', (Join-Path $ProjectDirectory 'docker-compose.yml'))

        # Shell variables otherwise override .env during Compose interpolation. Clear them only
        # in this setup process, then restore them, so the file is the source of truth.
        $savedEnvironment = @{}
        $names = @('DB_PASSWORD', 'JWT_SECRET', 'VERIFICATION_SECRET', 'GEMINI_API_KEY', 'APP_BASE_URL', 'CONTENT_CHECK_TIMEOUT_SECONDS')
        try {
            foreach ($name in $names) {
                $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
                [Environment]::SetEnvironmentVariable($name, $null, 'Process')
            }
            if ((Invoke-LinklyDockerQuery ($composeArguments + @('config', '--quiet'))).ExitCode -ne 0) {
                throw '.env or Compose configuration is invalid. Correct it and rerun setup; generated credentials are preserved.'
            }
            Write-Host 'Starting PostgreSQL, Redis and Linkly. The first build needs internet access...'
            $previousPreference = $ErrorActionPreference
            try {
                $ErrorActionPreference = 'Continue'
                & $script:LinklyDockerCommand @composeArguments up --build --detach
                $composeExitCode = $LASTEXITCODE
            } finally { $ErrorActionPreference = $previousPreference }
            if ($composeExitCode -ne 0) { throw 'Docker Compose could not start Linkly. Resolve the reported error and rerun; .env has been preserved.' }
            $binding = Invoke-LinklyDockerQuery ($composeArguments + @('port', 'app', '8080'))
            $portLine = @($binding.Output | Where-Object { $_ -match ':(\d+)\s*$' } | Select-Object -First 1)
            if ($binding.ExitCode -ne 0 -or $portLine.Count -ne 1) { throw 'Could not find the application port. Check docker compose ps.' }
            $port = [int]([regex]::Match($portLine[0], ':(\d+)\s*$').Groups[1].Value)
            Wait-LinklyApplication -Port $port
            Write-Host "Linkly is ready: http://localhost:$port/swagger-ui.html"
            Write-Host 'Basic shortening works immediately. For AI features, set GEMINI_API_KEY in .env and rerun this script.'
        } finally {
            foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
        }
    } finally {
        $setupLock.Dispose()
        Remove-Item -LiteralPath $lockPath -Force -ErrorAction SilentlyContinue
    }
    # Release the setup lock before following logs, so stop-linkly.cmd can run
    # in another window while this one remains open for the lifetime of the app.
    if ($FollowLogs) { Show-LinklyLogs -ProjectName $projectName }
}

# Dot-sourcing exposes helpers to the isolated setup tests without starting Docker.
if ($MyInvocation.InvocationName -ne '.') {
    try { Start-Linkly -ProjectDirectory (Split-Path $PSScriptRoot -Parent) -FollowLogs:$FollowLogs }
    catch { Write-Host ("Setup failed: " + $_.Exception.Message) -ForegroundColor Red; exit 1 }
}
