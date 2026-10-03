# Start the full local stack on Windows / PowerShell.
#
# Usage (run from the project root, or anywhere - paths are resolved):
#   .\scripts\start.ps1                 # start chain-worker + 4 Java services
#   .\scripts\start.ps1 -NoChain        # only the Java services
#   .\scripts\start.ps1 -ResetDb        # rebuild the demo database (same as -Dcampus.reset-db=true)
#
# Notes:
#   - Logs go to .logs/ (same convention as scripts/start.sh).
#   - The working directory is the project root so data-service finds .runtime.
#   - JVM options are written to a temporary @argfile because PowerShell splits
#     unquoted -D... arguments (this is the usual reason "ClassNotFoundException: /encoding=UTF-8"
#     shows up on Windows).
#   - ASCII only: Windows PowerShell 5.1 reads .ps1 files as ANSI unless they carry a UTF-8 BOM.
[CmdletBinding()]
param(
    [switch]$ResetDb,
    [switch]$NoChain,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not $JavaHome) { $JavaHome = 'D:\JAVA\jdk-jb-17' }
$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path $java)) { throw "java not found: $java (pass -JavaHome <JDK17 dir>)" }

$logs = Join-Path $root '.logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$runtime = Join-Path $root '.runtime'

$arguments = @(
    '-Dfile.encoding=UTF-8',
    ('-Dcampus.runtime=' + $runtime),
    ('-Djavax.net.ssl.trustStore=' + (Join-Path $runtime 'truststore.p12')),
    '-Djavax.net.ssl.trustStorePassword=campus-dev-tls-2024'
)
if ($ResetDb) { $arguments += '-Dcampus.reset-db=true' }
$argFile = Join-Path $logs 'jvm.args'
Set-Content -Path $argFile -Value $arguments -Encoding ASCII

function Start-CampusService {
    param([string]$Name, [string]$Jar)
    $jarPath = Join-Path $root $Jar
    if (-not (Test-Path $jarPath)) { throw "missing build output: $jarPath (run: mvn -DskipTests clean package)" }
    $log = Join-Path $logs ($Name + '.log')
    Write-Host ('==> starting ' + $Name + ' (log: ' + $log + ')')
    Start-Process -FilePath $java `
        -ArgumentList @(('@' + $argFile), '-jar', $jarPath) `
        -WorkingDirectory $root `
        -RedirectStandardOutput $log `
        -RedirectStandardError (Join-Path $logs ($Name + '.err.log')) `
        -WindowStyle Hidden | Out-Null
}

if (-not $NoChain) {
    $chain = Join-Path $root 'chain-worker'
    if (Test-Path (Join-Path $chain 'server.mjs')) {
        Write-Host '==> starting chain-worker (EVM / LSTM)'
        Start-Process -FilePath 'node' `
            -ArgumentList @('server.mjs') `
            -WorkingDirectory $chain `
            -RedirectStandardOutput (Join-Path $logs 'chain.log') `
            -RedirectStandardError (Join-Path $logs 'chain.err.log') `
            -WindowStyle Hidden | Out-Null
    }
}

Start-CampusService -Name 'gateway' -Jar 'gateway\target\gateway-1.0.0.jar'
Start-Sleep -Seconds 6
Start-CampusService -Name 'data-service' -Jar 'data-service\target\data-service-1.0.0.jar'
Start-CampusService -Name 'audit-service' -Jar 'audit-service\target\audit-service-1.0.0.jar'
Start-Sleep -Seconds 10
Start-CampusService -Name 'business-service' -Jar 'business-service\target\business-service-1.0.0.jar'

Write-Host ''
Write-Host 'Started. Allow ~20s, then check https://localhost:8443/system'
Write-Host ('Logs: ' + $logs)
