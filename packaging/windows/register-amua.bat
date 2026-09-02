@echo off
REM Re-enables the .amua file association after unregister-amua.bat turned it off.
REM Amua registers itself on start, so this only has to clear the opt-out flag.
REM Nothing here needs administrator rights.

setlocal
reg delete "HKCU\Software\Amua" /v NoFileAssociation /f >nul 2>&1
echo File association re-enabled. Start Amua once to complete it.
timeout /t 4 >nul
endlocal
