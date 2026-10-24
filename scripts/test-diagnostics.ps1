param([string]$JavaHome = 'C:\studyy\Android\Android Studio\jbr')

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputDirectory = Join-Path $projectRoot 'tests\build\diagnostics'
$javacPath = Join-Path $JavaHome 'bin\javac.exe'
$javaPath = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javacPath)) { throw "JDK not found: $javacPath. Pass -JavaHome with an installed JDK." }
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$productionPath = Join-Path $projectRoot 'app\src\main\java\com\wangqingli\app\core\ScanDiagnostics.java'
$testPath = Join-Path $projectRoot 'tests\DiagnosticsTests.java'
& $javacPath --release 8 -Xlint:-options -encoding UTF-8 -d $outputDirectory $productionPath $testPath
if ($LASTEXITCODE -ne 0) { throw "Diagnostics compilation failed (exit $LASTEXITCODE)." }
& $javaPath -cp $outputDirectory DiagnosticsTests
if ($LASTEXITCODE -ne 0) { throw "Diagnostics tests failed (exit $LASTEXITCODE)." }
