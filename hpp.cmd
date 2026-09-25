@echo off
rem  hpp.cmd -- compile + run the heightmap post-processing harness
rem  (com.example.heightmap.HeightPostProcess). No Minecraft on the classpath.
rem
rem  Usage (from anywhere):
rem    hpp.cmd <in.fmgh> [--seed N] [--detail[=amp]] [--out <base>]
rem
rem  Example:
rem    hpp.cmd run\fmg_heightmaps\granesia.fmgh --detail --out run\fmg_heightmaps\granesia.d
rem
rem  Run the synthetic-fixture generator instead:  hpp.cmd --synth sample 650 42
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

set "SRC=%ROOT%src\main\java\com\example\heightmap"
set "OUT=%ROOT%build\hpp-classes"
if not exist "%OUT%" mkdir "%OUT%"

"%JC%" -d "%OUT%" "%SRC%\HeightField.java" "%SRC%\HeightPostProcess.java" "%SRC%\SyntheticSample.java"
if errorlevel 1 exit /b 1

if /i "%~1"=="--synth" (
    shift
    "%JV%" -Xmx7g -cp "%OUT%" com.example.heightmap.SyntheticSample %2 %3 %4 %5 %6 %7 %8 %9
) else (
    "%JV%" -Xmx7g -cp "%OUT%" com.example.heightmap.HeightPostProcess %*
)
