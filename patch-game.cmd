@echo off
setlocal
cd /d "%~dp0"

set "PSEXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
where pwsh.exe >nul 2>nul && set "PSEXE=pwsh.exe"

set "APK=%~1"
if "%APK%"=="" set /p APK=Path to your own PVZH apk: 
if "%APK%"=="" goto noapk

"%PSEXE%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\patch-game.ps1" -Apk "%APK%" %2 %3 %4 %5 %6 %7 %8 %9
set "CODE=%ERRORLEVEL%"
goto done

:noapk
echo No APK given. Usage: patch-game.cmd "D:\downloads\pvzh.apk"
set "CODE=1"

:done
echo.
if "%CODE%"=="0" (
  echo [DONE] Patched APK is ready. Install it on BOTH phones.
) else (
  echo [FAILED] exit code %CODE%. Read the hints above.
)
echo.
pause
exit /b %CODE%
