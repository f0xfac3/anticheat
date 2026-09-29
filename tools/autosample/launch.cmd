@echo off
setlocal
cd /d "%~dp0"
if not exist "%~dp0.venv\Scripts\python.exe" (
  echo Run C:\anticheat-lab\automation\setup.cmd first.
  pause
  exit /b 1
)
"%~dp0.venv\Scripts\python.exe" -u "%~dp0runner.py" %*
set result=%errorlevel%
if not "%result%"=="0" echo The controller stopped. Read the reason above. Completed trials remain saved.
pause
exit /b %result%
