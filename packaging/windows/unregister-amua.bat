@echo off
REM Removes the .amua file association for the current user only.
REM Nothing here needs administrator rights: everything lives under HKEY_CURRENT_USER.

setlocal

REM Only clear the extension if it still points at Amua, so another program's
REM association is never taken away.
for /f "tokens=2,*" %%a in ('reg query "HKCU\Software\Classes\.amua" /ve 2^>nul ^| find "REG_SZ"') do (
    if /i "%%b"=="Amua.Model" reg delete "HKCU\Software\Classes\.amua" /f >nul 2>&1
)

reg delete "HKCU\Software\Classes\Amua.Model" /f >nul 2>&1
reg delete "HKCU\Software\Classes\Applications\Amua.exe" /f >nul 2>&1

REM Remember the choice, otherwise Amua would simply register itself again on the next start.
reg add "HKCU\Software\Amua" /v NoFileAssociation /t REG_DWORD /d 1 /f >nul 2>&1

REM Refresh the icons Explorer has cached.
ie4uinit.exe -show >nul 2>&1

echo Amua will no longer open .amua files by double-click.
echo Run register-amua.bat and start Amua to turn it back on.
timeout /t 4 >nul
endlocal
