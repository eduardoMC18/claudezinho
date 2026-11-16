# Gera o instalador do estudoTron 3000 (Windows):
#   1. backend Python -> executável (PyInstaller, onedir)      backend\dist-pkg\estudotron-backend\
#   2. interface (Vite) + processo principal (tsc) + dados do PDF.js
#   3. electron-builder -> app desempacotado + instalador NSIS  desktop\installer\
#
# Uso (PowerShell, na pasta desktop):   .\scripts\package.ps1 [-SkipBackend] [-DirOnly]
#   -SkipBackend  reaproveita backend\dist-pkg (se o backend não mudou)
#   -DirOnly      só a pasta instalável (win-unpacked), sem o instalador
# O instalador NÃO é assinado (sem certificado): o Windows/SmartScreen/antivírus pode avisar.
param([switch]$SkipBackend, [switch]$DirOnly)
$ErrorActionPreference = 'Stop'
$desktop = Split-Path $PSScriptRoot -Parent
$repo = Split-Path $desktop -Parent

function Run([string]$what, [scriptblock]$cmd) {
  Write-Host "==> $what" -ForegroundColor Cyan
  # ferramentas nativas escrevem logs em stderr; só o código de saída decide se falhou
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try { & $cmd } finally { $ErrorActionPreference = $prev }
  if ($LASTEXITCODE -ne 0) { throw "Falhou: $what (código $LASTEXITCODE)" }
}

if (-not $SkipBackend) {
  Push-Location "$repo\backend"
  try {
    $py = '.venv\Scripts\python.exe'
    if (-not (Test-Path $py)) { throw 'backend\.venv não existe (crie o venv e instale requirements-dev.txt)' }
    Run 'PyInstaller (backend)' { & $py -m PyInstaller --noconfirm --distpath dist-pkg --workpath build-pkg estudotron-backend.spec }
  } finally { Pop-Location }
}
if (-not (Test-Path "$repo\backend\dist-pkg\estudotron-backend\estudotron-backend.exe")) { throw 'backend\dist-pkg não encontrado (rode sem -SkipBackend)' }

Push-Location $desktop
try {
  Run 'tsc (processo principal)' { node node_modules\typescript\bin\tsc -p tsconfig.json }
  Run 'tsc (tipos da interface)' { node node_modules\typescript\bin\tsc -p tsconfig.renderer.json }
  Run 'Vite (interface)' { node node_modules\vite\bin\vite.js build }
  Run 'dados do PDF.js' { node scripts\copy-pdfjs-assets.mjs }
  if (Test-Path installer) { Remove-Item installer -Recurse -Force }
  if ($DirOnly) { Run 'electron-builder (--dir)' { node node_modules\electron-builder\cli.js --win --dir } }
  else { Run 'electron-builder (NSIS)' { node node_modules\electron-builder\cli.js --win } }
  Get-ChildItem installer -File | Where-Object { $_.Extension -eq '.exe' } | ForEach-Object { Write-Host ("Instalador: {0} ({1} MB)" -f $_.FullName, [math]::Round($_.Length / 1MB, 1)) -ForegroundColor Green }
} finally { Pop-Location }
