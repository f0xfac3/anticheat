@echo off
setlocal
set "lab=%~1"
if not defined lab set "lab=C:\anticheat-lab"
cd /d "%lab%\demo\source\anticheat"
"%lab%\automation\.venv\Scripts\python.exe" tools\research\evaluate.py --database "%lab%\demo\server\plugins\FoxAntiCheat\anticheat.sqlite" --output "%lab%\datasets\research\latest"
if errorlevel 1 goto done
if not exist "%lab%\demo\server\plugins\FoxAntiCheat\research" mkdir "%lab%\demo\server\plugins\FoxAntiCheat\research"
copy /y "%lab%\datasets\research\latest\REPORT.md" "%lab%\demo\server\plugins\FoxAntiCheat\research\REPORT.md" >nul
:done
pause
