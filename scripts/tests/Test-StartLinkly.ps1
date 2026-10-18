Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path $PSScriptRoot -Parent) 'start-linkly.ps1')
. (Join-Path (Split-Path $PSScriptRoot -Parent) 'stop-linkly.ps1')

# Run with Windows PowerShell 5.1, the same runtime used by start-linkly.cmd.
# Fixtures stay under ignored target/. These tests never start real containers.
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$testRoot = Join-Path $projectRoot ('target\setup-tests-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($testRoot) | Out-Null
$script:passed = 0
function Assert-Setup {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}
function New-Fixture {
    param([string]$Name)
    $directory = Join-Path $testRoot $Name
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot '.env.example') -Destination $directory
    return $directory
}
function Read-Environment {
    param([string]$Directory)
    return [IO.File]::ReadAllText((Join-Path $Directory '.env'))
}
function Write-Environment {
    param([string]$Directory, [string]$Text)
    [IO.File]::WriteAllText((Join-Path $Directory '.env'), $Text, (New-Object Text.UTF8Encoding($false)))
}
function Expect-Failure {
    param([scriptblock]$Action, [string]$Expected)
    $caught = $false
    try { & $Action } catch {
        $caught = $true
        Assert-Setup ($_.Exception.Message -like "*$Expected*") 'Unexpected failure message.'
    }
    Assert-Setup $caught 'Expected setup to fail.'
}
function Pass-Case {
    param([string]$Name)
    $script:passed++
    Write-Host "PASS: $Name"
}

try {
    $fresh = New-Fixture 'fresh setup with spaces'
    Initialize-LinklyEnvironment $fresh $false
    $text = Read-Environment $fresh
    $values = @('DB_PASSWORD', 'JWT_SECRET', 'VERIFICATION_SECRET') | ForEach-Object {
        (Get-LinklyEnvSetting $text $_).Value
    }
    foreach ($value in $values) {
        Assert-Setup ([Convert]::FromBase64String($value).Length -eq 48) 'Expected a 48-byte random credential.'
    }
    Assert-Setup (@($values | Select-Object -Unique).Count -eq 3) 'Credentials must be independent.'
    Assert-Setup ((Get-LinklyEnvSetting $text 'GEMINI_API_KEY').Value -eq '') 'Gemini must remain optional.'
    $bytes = [IO.File]::ReadAllBytes((Join-Path $fresh '.env'))
    Assert-Setup (-not ($bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191)) 'Unexpected UTF-8 BOM.'
    Pass-Case 'fresh setup generates three independent credentials, without Gemini'

    Initialize-LinklyEnvironment $fresh $true
    Assert-Setup ((Read-Environment $fresh) -ceq $text) 'Rerun changed configured credentials.'
    Assert-Setup ([Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $fresh '.env'))) -ceq [Convert]::ToBase64String($bytes)) 'Rerun changed file bytes.'
    Pass-Case 'rerun preserves the entire existing .env'

    $partial = New-Fixture 'partial'
    $existing = "# Keep this comment`r`nDB_PASSWORD='existing#password' # Keep password`r`nGEMINI_API_KEY=existing-ai-key`r`nAPP_BASE_URL=http://localhost:8080`r`n"
    Write-Environment $partial $existing
    Initialize-LinklyEnvironment $partial $true
    $partialText = Read-Environment $partial
    Assert-Setup ($partialText.StartsWith($existing)) 'Existing settings or comments were changed.'
    Assert-Setup ((Get-LinklyEnvSetting $partialText 'DB_PASSWORD').Value -ceq 'existing#password') 'Quoted password was changed.'
    Assert-Setup ((Get-LinklyEnvSetting $partialText 'JWT_SECRET').Value.Length -eq 64) 'Missing JWT key was not added.'
    Assert-Setup ((Get-LinklyEnvSetting $partialText 'VERIFICATION_SECRET').Value.Length -eq 64) 'Missing verification key was not added.'
    Pass-Case 'fills missing secrets while retaining an existing database password and AI key'

    $blank = New-Fixture 'blank placeholders'
    Write-Environment $blank "DB_PASSWORD=replace-with-password`nJWT_SECRET=`nVERIFICATION_SECRET=<new-secret>`n"
    Initialize-LinklyEnvironment $blank $false
    foreach ($name in @('DB_PASSWORD', 'JWT_SECRET', 'VERIFICATION_SECRET')) {
        Assert-Setup ((Get-LinklyEnvSetting (Read-Environment $blank) $name).Value.Length -eq 64) 'Placeholder was not replaced.'
    }
    Assert-Setup (@(Get-ChildItem -LiteralPath $blank -Filter '.env.setup-*' -Force).Count -eq 0) 'Temporary credential file leaked.'
    Pass-Case 'replaces empty/template credentials and cleans up temporary files'

    $blocked = New-Fixture 'existing database without env'
    Expect-Failure { Initialize-LinklyEnvironment $blocked $true } 'Restore its existing password'
    Assert-Setup (-not (Test-Path -LiteralPath (Join-Path $blocked '.env'))) 'Blocked setup wrote credentials.'
    Write-Environment $blocked "DB_PASSWORD=`nJWT_SECRET=retained-value`n"
    $before = Read-Environment $blocked
    Expect-Failure { Initialize-LinklyEnvironment $blocked $true } 'Restore its existing password'
    Assert-Setup ((Read-Environment $blocked) -ceq $before) 'Blocked setup modified .env.'
    Pass-Case 'existing data with missing password is left untouched'

    $duplicate = New-Fixture 'duplicate settings'
    Write-Environment $duplicate "DB_PASSWORD=retained`nJWT_SECRET=one`nJWT_SECRET=two`n"
    $before = Read-Environment $duplicate
    Expect-Failure { Initialize-LinklyEnvironment $duplicate $false } 'Duplicate JWT_SECRET'
    Assert-Setup ((Read-Environment $duplicate) -ceq $before) 'Duplicate rejection modified .env.'
    Pass-Case 'ambiguous duplicate settings are rejected before any write'

    # Exercise the orchestration against a fake native executable, including failures.
    # No Docker engine or network connection is used by this test.
    $savedPath = $env:PATH
    $savedPassword = $env:DB_PASSWORD
    $savedExit = $env:LINKLY_TEST_DOCKER_EXIT
    $savedProject = $env:COMPOSE_PROJECT_NAME
    try {
        $fakeBin = Join-Path $testRoot 'fake bin'
        [IO.Directory]::CreateDirectory($fakeBin) | Out-Null
        [IO.File]::WriteAllText((Join-Path $fakeBin 'docker.cmd'), "@exit /b %LINKLY_TEST_DOCKER_EXIT%`r`n", [Text.Encoding]::ASCII)
        $env:PATH = $fakeBin + ';' + $savedPath
        $env:DB_PASSWORD = 'shell-value-that-must-not-override-file'
        $env:COMPOSE_PROJECT_NAME = 'linkly-setup-test'
        $script:queryCalls = New-Object System.Collections.Generic.List[string]
        $script:healthChecked = $false
        $script:failConfig = $false
        $script:logContainer = @('aaaaaaaaaaaa')
        function Wait-LinklyDocker { }
        function Test-LinklyExistingDatabase { param([string]$ProjectName); return $false }
        function Invoke-LinklyDockerQuery {
            param([string[]]$ArgumentList)
            $script:queryCalls.Add(($ArgumentList -join '|'))
            if ($ArgumentList[0] -eq 'ps') {
                Assert-Setup (-not (Test-Path -LiteralPath (Join-Path $fresh '.linkly-setup.lock'))) 'Live logs hold the setup lock and block shutdown.'
                return [pscustomobject]@{ ExitCode = 0; Output = $script:logContainer }
            }
            if ($ArgumentList -contains 'config') {
                Assert-Setup ([string]::IsNullOrEmpty($env:DB_PASSWORD)) 'Shell password still overrides .env.'
                if ($script:failConfig) { return [pscustomobject]@{ ExitCode = 1; Output = @() } }
            }
            $output = if ($ArgumentList -contains 'port') { @('127.0.0.1:18080') } else { @() }
            return [pscustomobject]@{ ExitCode = 0; Output = $output }
        }
        function Wait-LinklyApplication {
            param([int]$Port)
            Assert-Setup ($Port -eq 18080) 'Incorrect published app port.'
            $script:healthChecked = $true
        }
        $env:LINKLY_TEST_DOCKER_EXIT = '0'
        Start-Linkly $fresh
        Assert-Setup $script:healthChecked 'Setup skipped the application health check.'
        Assert-Setup ($env:DB_PASSWORD -ceq 'shell-value-that-must-not-override-file') 'Shell environment was not restored.'
        Assert-Setup (($script:queryCalls -join "`n").Contains('--env-file|' + (Join-Path $fresh '.env'))) 'Compose did not use the explicit .env path.'
        Assert-Setup (-not (Test-Path -LiteralPath (Join-Path $fresh '.linkly-setup.lock'))) 'Setup lock was left behind.'
        Pass-Case 'startup uses explicit .env, restores shell settings, and checks the published app port'

        $script:healthChecked = $false
        $env:LINKLY_TEST_DOCKER_EXIT = '7'
        Expect-Failure { Start-Linkly $fresh } 'Docker Compose could not start'
        Assert-Setup (-not $script:healthChecked) 'Failed Compose run reported health success.'
        Assert-Setup ((Read-Environment $fresh) -ceq $text) 'Failed startup rotated credentials.'
        Assert-Setup ($env:DB_PASSWORD -ceq 'shell-value-that-must-not-override-file') 'Failure did not restore shell settings.'
        Pass-Case 'Compose failure propagates and preserves credentials'

        $script:failConfig = $true
        Expect-Failure { Start-Linkly $fresh } 'Compose configuration is invalid'
        Assert-Setup (-not (Test-Path -LiteralPath (Join-Path $fresh '.linkly-setup.lock'))) 'Failure left a setup lock.'
        Pass-Case 'invalid configuration fails cleanly and releases the setup lock'

        $script:failConfig = $false
        $env:LINKLY_TEST_DOCKER_EXIT = '0'
        Start-Linkly $fresh -FollowLogs
        Assert-Setup (($script:queryCalls -join "`n").Contains('label=com.docker.compose.service=app')) 'Live logs did not select the app service.'
        Pass-Case 'live logs start after readiness and after the setup lock is released'

        $env:LINKLY_TEST_DOCKER_EXIT = '7'
        Expect-Failure { Show-LinklyLogs 'linkly-setup-test' } 'live log stream failed'
        $script:logContainer = @()
        Show-LinklyLogs 'linkly-setup-test'
        Pass-Case 'log failures are reported and an app already stopped ends normally'

        $script:runningServices = @{ app = 'aaaaaaaaaaaa'; postgres = 'bbbbbbbbbbbb'; redis = 'cccccccccccc' }
        $script:stopCalls = New-Object System.Collections.Generic.List[string]
        $script:failDiscovery = $false
        $script:failAppStop = $false
        function Invoke-LinklyDockerQuery {
            param([string[]]$ArgumentList)
            if ($ArgumentList[0] -eq 'ps') {
                Assert-Setup ($ArgumentList -contains 'label=com.docker.compose.project=linkly-setup-test') 'Shutdown selected another Compose project.'
                $serviceLabel = @($ArgumentList | Where-Object { $_ -like 'label=com.docker.compose.service=*' })[0]
                $service = $serviceLabel.Substring($serviceLabel.LastIndexOf('=') + 1)
                if ($script:failDiscovery -and $service -eq 'postgres') {
                    return [pscustomobject]@{ ExitCode = 1; Output = @() }
                }
                $output = if ($script:runningServices.ContainsKey($service)) { @($script:runningServices[$service]) } else { @() }
                return [pscustomobject]@{ ExitCode = 0; Output = $output }
            }
            if ($ArgumentList[0] -eq 'stop') {
                Assert-Setup ($ArgumentList[1] -eq '--time' -and $ArgumentList[2] -eq '30') 'Shutdown did not allow graceful termination.'
                $script:stopCalls.Add($ArgumentList[3])
                if ($script:failAppStop -and $ArgumentList[3] -eq 'aaaaaaaaaaaa') {
                    return [pscustomobject]@{ ExitCode = 1; Output = @() }
                }
                return [pscustomobject]@{ ExitCode = 0; Output = @() }
            }
            Assert-Setup ($ArgumentList[0] -eq 'info') 'Shutdown issued an unexpected Docker operation.'
            return [pscustomobject]@{ ExitCode = 0; Output = @() }
        }
        Stop-Linkly $fresh
        Assert-Setup (($script:stopCalls -join ',') -eq 'aaaaaaaaaaaa,bbbbbbbbbbbb,cccccccccccc') 'Shutdown did not stop the app before its dependencies.'
        Assert-Setup ((Read-Environment $fresh) -ceq $text) 'Shutdown modified credentials.'
        Pass-Case 'shutdown stops only this project, app first, preserving .env'

        $noEnv = New-Fixture 'shutdown without credentials'
        $script:stopCalls.Clear()
        Stop-Linkly $noEnv
        Assert-Setup (-not (Test-Path -LiteralPath (Join-Path $noEnv '.env'))) 'Shutdown generated credentials.'
        Pass-Case 'shutdown works without .env or credential generation'

        $script:runningServices = @{}
        $script:stopCalls.Clear()
        Stop-Linkly $fresh
        Assert-Setup ($script:stopCalls.Count -eq 0) 'Already stopped stack caused container mutations.'
        Pass-Case 'repeated shutdown is harmless'

        $script:runningServices = @{ app = 'aaaaaaaaaaaa'; postgres = 'bbbbbbbbbbbb'; redis = 'cccccccccccc' }
        $script:failAppStop = $true
        Expect-Failure { Stop-Linkly $fresh } 'Could not stop Linkly app'
        Assert-Setup (($script:stopCalls -join ',') -eq 'aaaaaaaaaaaa') 'Dependencies stopped after app shutdown failed.'
        Assert-Setup (-not (Test-Path -LiteralPath (Join-Path $fresh '.linkly-setup.lock'))) 'Shutdown failure left the setup lock.'
        Pass-Case 'failed app shutdown keeps dependencies running and releases the lock'

        $script:failAppStop = $false
        $script:failDiscovery = $true
        $script:stopCalls.Clear()
        Expect-Failure { Stop-Linkly $fresh } 'Nothing was stopped'
        Assert-Setup ($script:stopCalls.Count -eq 0) 'Shutdown acted on incomplete container discovery.'
        Pass-Case 'container discovery failures stop nothing'

        $heldLock = [IO.File]::Open((Join-Path $fresh '.linkly-setup.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
        try { Expect-Failure { Stop-Linkly $fresh } 'already in progress' }
        finally {
            $heldLock.Dispose()
            Remove-Item -LiteralPath (Join-Path $fresh '.linkly-setup.lock') -Force
        }
        Pass-Case 'shutdown cannot race an in-progress startup'
    } finally {
        $env:PATH = $savedPath
        $env:DB_PASSWORD = $savedPassword
        $env:LINKLY_TEST_DOCKER_EXIT = $savedExit
        $env:COMPOSE_PROJECT_NAME = $savedProject
    }
    Write-Host "$script:passed setup test cases passed."
} finally {
    # Resolve before recursive deletion; only remove this run's unique fixture directory.
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $allowed = [IO.Path]::GetFullPath((Join-Path $projectRoot 'target')) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe fixture cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
