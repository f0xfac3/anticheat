@echo off
if "%~1"=="" (
  echo Usage: reach_edge_on.cmd 3.3 PAIR-FOLDER
  pause
  exit /b 1
)
if "%~2"=="" (
  echo Usage: reach_edge_on.cmd 3.3 PAIR-FOLDER
  pause
  exit /b 1
)
"%~dp0automation\.venv\Scripts\python.exe" -u "%~dp0automation\reach.py" on --setting %~1 --pair %~2
pause
