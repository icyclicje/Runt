# Builds dist\MivimooseScanner.exe
#   powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not (Test-Path .venv\Scripts\python.exe)) {
    Write-Host 'Creating virtual environment...'
    py -3 -m venv .venv
}
$py = '.venv\Scripts\python.exe'
& $py -m pip install --upgrade pip -q
& $py -m pip install -r requirements.txt -q

# App icon (drawn by the app itself)
New-Item -ItemType Directory -Force assets | Out-Null
& $py -c "from app.hud import icon_image; im = icon_image(256); im.save('assets/icon.ico', sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])"

$openvrDll = & $py -c "import openvr, os; print(os.path.join(os.path.dirname(openvr.__file__), 'libopenvr_api_64.dll'))"

& $py -m PyInstaller --noconfirm --clean --onefile --windowed `
    --name MivimooseScanner `
    --icon assets\icon.ico `
    --add-data "web;web" `
    --add-binary "$openvrDll;openvr" `
    --hidden-import pygrabber.dshow_graph `
    --collect-submodules comtypes `
    --exclude-module tkinter --exclude-module matplotlib --exclude-module pytest `
    run.py

Write-Host ''
Write-Host "Built: $PSScriptRoot\dist\MivimooseScanner.exe"
