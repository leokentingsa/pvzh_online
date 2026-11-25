@echo off
setlocal
cd /d "%~dp0"

rem Prefer PowerShell 7 if present, otherwise the built-in Windows PowerShell.
set "PSEXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
where pwsh.exe >nul 2>nul && set "PSEXE=pwsh.exe"

set "ARGS=%*"
set "MODE=build"
if /i not "%ARGS%"=="%ARGS:CheckOnly=%" set "MODE=check"

"%PSEXE%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
set "CODE=%ERRORLEVEL%"

echo.
if "%CODE%"=="0" goto ok
echo [FAILED] exit code %CODE%. Follow the hints above, or share build-*.log
goto done
:ok
if "%MODE%"=="check" echo [DONE] Environment check passed. Run build.cmd again without -CheckOnly to build.
if "%MODE%"=="build" echo [DONE] Build succeeded. APK path is printed above.
:done
echo.
pause
exit /b %CODE%
