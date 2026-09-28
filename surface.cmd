@echo off
rem  surface.cmd -- run the layered-surface decisions (com.example.surface) on a
rem  terrain bake without Minecraft, writing a column dump that
rem  tools\fmgtrees\render_surface_dump.py draws with the Conquest textures.
rem
rem  Usage (from the repo root):
rem    surface.cmd <bakeBase> <out.json> [--center X Z] [--size 256] [--palette <id>] [--seed N]
rem
rem  Example:
rem    surface.cmd ..\refinemap\outj\EnciaNow run\surface\encia.json --center 1200 -300 --size 256
rem    python tools\fmgtrees\render_surface_dump.py run\surface\encia.json
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
for /f "delims=" %%G in ('dir /s /b "%USERPROFILE%\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson\gson-*.jar" 2^>nul ^| findstr /v "sources javadoc"') do set "GSON=%%G"
if not defined GSON (
    echo Could not find a gson jar in the Gradle cache - run a Gradle build once first.
    exit /b 1
)

set "SRC=%ROOT%src\main\java\com\example"
set "OUT=%ROOT%build\surface-classes"
if not exist "%OUT%" mkdir "%OUT%"

set "ARGS=%OUT%\sources.txt"
type nul > "%ARGS%"
for %%F in ("%SRC%\surface\*.java" "%SRC%\terrain\*.java" "%SRC%\heightmap\HeightPostProcess.java" "%SRC%\heightmap\HeightField.java" "%SRC%\util\CatmullRomSpline.java") do (
    set "P=%%~fF"
    echo "!P:\=/!">> "%ARGS%"
)
"%JC%" -encoding UTF-8 -cp "%GSON%" -d "%OUT%" "@%ARGS%"
if errorlevel 1 exit /b 1

pushd "%ROOT%"
"%JV%" -Xmx4g -cp "%OUT%;%GSON%" com.example.surface.SurfaceCli %*
set "RC=%ERRORLEVEL%"
popd
exit /b %RC%
