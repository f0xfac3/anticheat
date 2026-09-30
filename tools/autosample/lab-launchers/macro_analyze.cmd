@echo off
"%~dp0automation\.venv\Scripts\python.exe" -u "%~dp0automation\macro.py" analyze %*
pause
