@echo off
"%~dp0automation\.venv\Scripts\python.exe" -u "%~dp0automation\reach.py" off --distances 3.4,3.5,3.6,3.7,3.8 %*
pause
