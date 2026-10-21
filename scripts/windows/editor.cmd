@echo off
python -X utf8 "%~dp0editor.py" %*
exit /b %errorlevel%
