@echo off
echo === Setting up Visual Studio 2026 environment ===
call "C:\Program Files\Microsoft Visual Studio\18\Insiders\VC\Auxiliary\Build\vcvars64.bat"
if %ERRORLEVEL% neq 0 (
    echo FAILED: vcvars64.bat
    exit /b 1
)

echo === Adding vswhere to PATH ===
set "PATH=%PATH%;C:\Program Files (x86)\Microsoft Visual Studio\Installer"

echo === Running Spring Boot AOT + GraalVM Native Image ===
cd /d d:\gitcode\wuwei\wuwei-core
D:\codesoft\gradle-9.3.1\bin\gradle.bat nativeCompile --no-daemon
if %ERRORLEVEL% neq 0 (
    echo.
    echo === Native compile FAILED, check errors above ===
    exit /b 1
)

echo.
echo === SUCCESS! Native image built ===
dir build\native\nativeCompile\*.exe 2>nul
