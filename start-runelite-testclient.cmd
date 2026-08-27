@echo off
setlocal EnableExtensions

title OSRS Flip Tracker - RuneLite testclient

set "JAVA_HOME=C:\Users\Steff\.jdks\openjdk-26.0.1"
set "GRADLE_USER_HOME=C:\Users\Steff\Documents\Codex\2026-08-01\het\work\gradle-cache-runelite-focus"
set "PLUGIN_HOME=C:\Users\Steff\Documents\Codex\2026-08-01\het\osrs-flipper-runelite-sync"

cd /d "%PLUGIN_HOME%"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo FOUT: Java werd niet gevonden in:
    echo %JAVA_HOME%
    echo.
    pause
    exit /b 1
)

if not exist "%PLUGIN_HOME%\gradlew.bat" (
    echo FOUT: gradlew.bat werd niet gevonden in:
    echo %PLUGIN_HOME%
    echo.
    pause
    exit /b 1
)

echo RuneLite-testclient wordt gestart...
echo Pluginmap: %PLUGIN_HOME%
echo.

call "%PLUGIN_HOME%\gradlew.bat" run --no-daemon
set "RUNELITE_EXIT_CODE=%ERRORLEVEL%"

if not "%RUNELITE_EXIT_CODE%"=="0" (
    echo.
    echo De RuneLite-testclient kon niet worden gestart.
    echo Exitcode: %RUNELITE_EXIT_CODE%
    pause
)

exit /b %RUNELITE_EXIT_CODE%
