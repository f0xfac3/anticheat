@echo off
"%~dp0automation\.venv\Scripts\python.exe" "%~dp0demo\collection\dataset_tool.py" audit "%~dp0datasets\raw"
pause
