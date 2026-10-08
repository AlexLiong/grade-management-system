<#
  Stop the local stack started by scripts/start.ps1.

  Usage:
    .\scripts\stop.ps1                  # stop the 4 Java services + chain-worker
    .\scripts\stop.ps1 -KeepChain       # stop only the 4 Java services
    .\scripts\stop.ps1 -IncludeVite     # also stop the Vite dev server (port 5173)
    .\scripts\stop.ps1 -List            # only show what is running, stop nothing

  Notes:
    - Processes are matched by their command line (the 4 jar names and chain-worker's
      server.mjs), never by image name alone. `Stop-Process -Name java` would also kill
      unrelated Java programs such as an IDE run, so it is deliberately not used.
    - Ports 8443 / 9441 / 9442 / 9443 / 9545 are checked afterwards and reported.
    - ASCII only: Windows PowerShell 5.1 reads .ps1 files as ANSI unless they carry a
      UTF-8 BOM, which is why this file keeps to ASCII like start.ps1.
#>
[CmdletBinding()]
param(
    [switch]$KeepChain,
    [switch]$IncludeVite,
    [switch]$List,
    [int]$WaitSeconds = 15
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$jarPattern = 'gateway-1\.0\.0\.jar|data-service-1\.0\.0\.jar|audit-service-1\.0\.0\.jar|business-service-1\.0\.0\.jar'
$servicePorts = @(8443, 9441, 9442, 9443)
$chainPort = 9545
$vitePort = 5173

function Get-Matching {
    param([string]$Image, [string]$Pattern, [string]$Mode)
    Get-CimInstance Win32_Process -Filter ("Name='" + $Image + "'") -ErrorAction SilentlyContinue |
        Where-Object {
            $_.CommandLine -and (
                ($Mode -eq 'regex' -and $_.CommandLine -match $Pattern) -or
                ($Mode -eq 'like' -and $_.CommandLine -like $Pattern)
            )
        }
}

function Describe {
    param($Proc)
    $tail = ($Proc.CommandLine -split '\\')[-1]
    if ($tail.Length -gt 70) { $tail = $tail.Substring(0, 70) + '...' }
    return ('PID {0}  {1}' -f $Proc.ProcessId, $tail)
}

Write-Host ''
Write-Host '=== current state ==='

$javaProcs = @(Get-Matching -Image 'java.exe' -Pattern $jarPattern -Mode 'regex')
$chainProcs = @(Get-Matching -Image 'node.exe' -Pattern '*server.mjs*' -Mode 'like')
$viteProcs = @(Get-Matching -Image 'node.exe' -Pattern '*vite*' -Mode 'like')

if ($javaProcs.Count -eq 0) { Write-Host '  Java services : none' }
else { $javaProcs | ForEach-Object { Write-Host ('  Java service  : ' + (Describe $_)) } }

if ($chainProcs.Count -eq 0) { Write-Host '  chain-worker  : none' }
else { $chainProcs | ForEach-Object { Write-Host ('  chain-worker  : ' + (Describe $_)) } }

if ($viteProcs.Count -eq 0) { Write-Host '  Vite dev      : none' }
else { $viteProcs | ForEach-Object { Write-Host ('  Vite dev      : ' + (Describe $_)) } }

if ($List) {
    Write-Host ''
    Write-Host 'List only (-List); nothing was stopped.'
    return
}

$stopped = 0

Write-Host ''
Write-Host '=== stopping ==='
foreach ($p in $javaProcs) {
    try {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop
        Write-Host ('  stopped java  ' + (Describe $p))
        $stopped++
    } catch {
        Write-Host ('  failed  java  PID ' + $p.ProcessId + ' : ' + $_.Exception.Message)
    }
}

if (-not $KeepChain) {
    foreach ($p in $chainProcs) {
        try {
            Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop
            Write-Host ('  stopped chain ' + (Describe $p))
            $stopped++
        } catch {
            Write-Host ('  failed  chain PID ' + $p.ProcessId + ' : ' + $_.Exception.Message)
        }
    }
} elseif ($chainProcs.Count -gt 0) {
    Write-Host '  chain-worker kept running (-KeepChain)'
}

if ($IncludeVite) {
    foreach ($p in $viteProcs) {
        try {
            Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop
            Write-Host ('  stopped vite  ' + (Describe $p))
            $stopped++
        } catch {
            Write-Host ('  failed  vite  PID ' + $p.ProcessId + ' : ' + $_.Exception.Message)
        }
    }
} elseif ($viteProcs.Count -gt 0) {
    Write-Host '  Vite dev kept running (pass -IncludeVite to stop it)'
}

# ------------------------------------------------------------------ verify
Write-Host ''
Write-Host '=== ports after stop ==='
$deadline = (Get-Date).AddSeconds($WaitSeconds)
$pending = New-Object System.Collections.ArrayList
foreach ($port in ($servicePorts + $chainPort + $vitePort)) { [void]$pending.Add($port) }

$released = @{}
while ((Get-Date) -lt $deadline -and $pending.Count -gt 0) {
    foreach ($port in @($pending)) {
        $client = New-Object System.Net.Sockets.TcpClient
        try {
            $busy = $client.ConnectAsync('127.0.0.1', $port).Wait(300)
        } catch {
            $busy = $false
        } finally {
            $client.Dispose()
        }
        if (-not $busy) {
            $released[$port] = $true
            [void]$pending.Remove($port)
        }
    }
    if ($pending.Count -gt 0) { Start-Sleep -Milliseconds 500 }
}

foreach ($port in ($servicePorts + $chainPort + $vitePort)) {
    $name = switch ($port) {
        8443 { 'gateway      ' } 9441 { 'business     ' } 9442 { 'data         ' }
        9443 { 'audit        ' } 9545 { 'chain-worker ' } 5173 { 'vite dev     ' }
        default { 'unknown      ' }
    }
    if ($released.ContainsKey($port)) {
        Write-Host ('  {0} {1}  released' -f $port, $name)
    } else {
        Write-Host ('  {0} {1}  STILL LISTENING' -f $port, $name)
    }
}

Write-Host ''
Write-Host ('Stopped {0} process(es).' -f $stopped)
Write-Host 'Data kept: .runtime (encrypted H2 database, ledger, chain data, secrets).'
Write-Host 'Restart with: .\scripts\start.ps1'
