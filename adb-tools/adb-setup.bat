@echo off
rem ============================================================================
rem  GKD Tejiao - one-click ADB setup (launcher)
rem
rem  NOTE: this file is ASCII-only on purpose. cmd.exe parses .bat/.cmd using the
rem        OEM codepage (GBK on zh-CN Windows), so non-ASCII text here would be
rem        corrupted. All Chinese messages live in adb-setup.ps1, which is
rem        UTF-8 *with BOM* so Windows PowerShell 5.1 decodes it correctly.
rem
rem  Keep adb-setup.bat and adb-setup.ps1 in the SAME folder.
rem ============================================================================
setlocal
set "HERE=%~dp0"
set "PS1=%HERE%adb-setup.ps1"

if not exist "%PS1%" (
  echo.
  echo [ERROR] adb-setup.ps1 was not found next to this file.
  echo         adb-setup.bat and adb-setup.ps1 must stay in the SAME folder.
  echo         Missing: %PS1%
  echo.
  pause
  exit /b 1
)

where powershell >nul 2>nul
if errorlevel 1 (
  echo.
  echo [ERROR] Windows PowerShell was not found on this system.
  echo.
  pause
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*
set "RC=%ERRORLEVEL%"

echo.
if "%RC%"=="0" (echo [DONE] exit code 0) else (echo [FAILED] exit code %RC%)
echo.
pause
exit /b %RC%
