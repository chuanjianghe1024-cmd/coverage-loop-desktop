$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$installer = Get-ChildItem "$repo/apps/desktop/src-tauri/target/release/bundle/nsis/*-setup.exe" | Select-Object -First 1
if (!$installer) { throw 'Tauri installer missing' }
$testRoot = Join-Path $env:RUNNER_TEMP ('coverage-tauri-' + [guid]::NewGuid())
$installed = Join-Path $testRoot 'installed 应用'
$project = Join-Path $testRoot 'project'
New-Item -ItemType Directory -Force $testRoot,$project | Out-Null
Copy-Item "$repo/backend/src/test/resources/sample-project/*" $project -Recurse
$setup = Start-Process -FilePath $installer.FullName -ArgumentList @('/S', "/D=$installed") -Wait -PassThru
if ($setup.ExitCode -ne 0) { throw "Installer exit: $($setup.ExitCode)" }
$exe = Join-Path $installed 'coverage-loop-tauri.exe'
if (!(Test-Path $exe)) { throw 'Installed executable missing' }
if (!(Test-Path "$installed/runtime/bin/java.exe")) { throw 'Bundled Java runtime missing' }
if (!(Test-Path "$installed/engine/coverage-loop-engine.jar")) { throw 'Bundled engine missing' }
& "$installed/runtime/bin/java.exe" -version
if ($LASTEXITCODE -ne 0) { throw 'Bundled Java runtime cannot start' }
$env:COVERAGE_DATA_DIR = Join-Path $testRoot 'workspace'
$env:COVERAGE_SMOKE_PROJECT = Join-Path $project 'pom.xml'
$env:COVERAGE_SMOKE_REPORT = Join-Path $testRoot 'smoke.json'
try {
  $process = Start-Process -FilePath $exe -PassThru
  if (!$process.WaitForExit(90000)) {
    Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -like "$installed*" } | Select-Object Name,ProcessId,ParentProcessId,CommandLine | Format-List
    Stop-Process -Id $process.Id -Force
    throw 'Installed app smoke timed out'
  }
  if (!(Test-Path $env:COVERAGE_SMOKE_REPORT)) { throw 'Installed app did not produce a smoke report' }
  $report = Get-Content $env:COVERAGE_SMOKE_REPORT -Raw | ConvertFrom-Json
  if ($report.status -ne 'passed' -or $process.ExitCode -ne 0) { throw ($report | ConvertTo-Json -Depth 10) }
  $report | ConvertTo-Json -Depth 10
  $report | ConvertTo-Json -Depth 10 | Set-Content "$repo/tauri-smoke.json" -Encoding utf8
  $leftover = Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -like "$installed*" }
  if ($leftover) { throw 'Packaged app left a managed process running' }
} finally {
  if (Test-Path (Join-Path $testRoot 'smoke.log')) { Get-Content (Join-Path $testRoot 'smoke.log'); Copy-Item (Join-Path $testRoot 'smoke.log') "$repo/tauri-smoke.log" }
  if (Test-Path (Join-Path $testRoot 'smoke.json')) { Copy-Item (Join-Path $testRoot 'smoke.json') "$repo/tauri-smoke.json" }
  Remove-Item Env:COVERAGE_DATA_DIR,Env:COVERAGE_SMOKE_PROJECT,Env:COVERAGE_SMOKE_REPORT -ErrorAction SilentlyContinue
}
$uninstaller = Get-ChildItem "$installed/*uninstall*.exe" | Select-Object -First 1
if ($uninstaller) { Start-Process -FilePath $uninstaller.FullName -ArgumentList '/S' -Wait }
