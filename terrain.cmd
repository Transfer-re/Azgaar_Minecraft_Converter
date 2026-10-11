@echo off
rem  terrain.cmd -- run the blended-terrain bake (com.example.terrain) on an FMG
rem  JSON export without Minecraft, writing .npy previews for refinemap\render3d.py.
rem
rem  Usage (from anywhere):
rem    terrain.cmd <map.json> <outBase> [--scale 12] [--max-y 768] [--peak 420] [--gamma 1.8] [--seed N] [--no-roads]
rem
rem  Example:
rem    terrain.cmd "..\maps\Encia Full 2026-08-29-22-43.json" ..\..\refinemap\outj\Encia
rem    python ..\..\refinemap\render3d.py ..\..\refinemap\outj\Encia --auto 4
setlocal enabledelayedexpansion

set "ROOT=%~dp0"
set "JHOME=%JAVA_HOME%"
if not exist "!JHOME!\bin\javac.exe" set "JHOME=C:\Program Files\Microsoft\jdk-17.0.14.7-hotspot"
if not exist "!JHOME!\bin\javac.exe" set "JHOME="
if defined JHOME (
    set "JC=!JHOME!\bin\javac.exe"
    set "JV=!JHOME!\bin\java.exe"
) else (
    set "JC=javac"
    set "JV=java"
)

rem Gson is the only dependency; take it from the Gradle cache
set "GSON="
for /f "delims=" %%G in ('dir /s /b "%USERPROFILE%\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson\gson-*.jar" 2^>nul ^| findstr /v sources') do set "GSON=%%G"
if not defined GSON (
    echo Could not find a gson jar in the Gradle cache - run a Gradle build once first.
    exit /b 1
)

set "SRC=%ROOT%src\main\java\com\example"
set "OUT=%ROOT%build\terrain-classes"
if not exist "%OUT%" mkdir "%OUT%"

rem javac does not expand quoted wildcards on Windows: list the sources in an @argfile
rem (forward slashes: backslashes are escapes inside a quoted argfile entry)
set "ARGS=%OUT%\sources.txt"
type nul > "%ARGS%"
for %%F in ("%SRC%\terrain\*.java" "%SRC%\heightmap\HeightPostProcess.java" "%SRC%\heightmap\HeightField.java" "%SRC%\util\CatmullRomSpline.java") do (
    set "P=%%~fF"
    echo "!P:\=/!">> "%ARGS%"
)
"%JC%" -encoding UTF-8 -cp "%GSON%" -d "%OUT%" "@%ARGS%"
if errorlevel 1 exit /b 1

"%JV%" -Xmx4g -cp "%OUT%;%GSON%" com.example.terrain.TerrainCli %*
