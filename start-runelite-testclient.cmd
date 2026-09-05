@echo off
setlocal EnableExtensions DisableDelayedExpansion

title OSRS Flip Tracker - RuneLite testclient

set "PLUGIN_HOME=%~dp0"

if defined JAVA_HOME goto java_ready
where java.exe >nul 2>&1
if not errorlevel 1 goto java_ready
for /f "usebackq delims=" %%J in (`powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PLUGIN_HOME%find-local-jdk.ps1"`) do set "JAVA_HOME=%%J"
if defined JAVA_HOME goto java_ready
echo FOUT: geen Java gevonden. Installeer een JDK van Java 17 of hoger en stel JAVA_HOME in.
exit /b 1

:java_ready
if /i "%~1"=="-CheckJava" goto check_java

if not exist "%PLUGIN_HOME%\gradlew.bat" (
    echo FOUT: gradlew.bat werd niet gevonden in:
    echo %PLUGIN_HOME%
    echo.
    pause
    exit /b 1
)

echo RuneLite-testclient wordt gestart...
echo Pluginmap: %PLUGIN_HOME%
echo Gradle vereist Java 17 of hoger via JAVA_HOME of PATH; plugin-bytecode blijft Java 11.
if defined JAVA_HOME echo Java-map: %JAVA_HOME%
echo.

pushd "%PLUGIN_HOME%" || exit /b 1
call "%PLUGIN_HOME%\gradlew.bat" run --no-daemon
set "RUNELITE_EXIT_CODE=%ERRORLEVEL%"
popd

if not "%RUNELITE_EXIT_CODE%"=="0" (
    echo.
    echo De RuneLite-testclient kon niet worden gestart.
    echo Exitcode: %RUNELITE_EXIT_CODE%
    pause
)

exit /b %RUNELITE_EXIT_CODE%

:check_java
if defined JAVA_HOME (
    if not exist "%JAVA_HOME%\bin\javac.exe" (
        echo FOUT: JAVA_HOME verwijst niet naar een JDK.
        exit /b 1
    )
    "%JAVA_HOME%\bin\java.exe" -version
) else (
    java.exe -version
)
exit /b %ERRORLEVEL%
