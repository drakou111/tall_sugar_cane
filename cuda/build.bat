@echo off
REM Builds the chain filter for several architectures at once.
REM
REM Multi-architecture on purpose. A binary built with a single -arch will not load on any
REM other card, and the Java side cannot tell that apart from having no GPU at all -- it
REM reports "no usable GPU" and quietly runs about 4.5x slower on the CPU. A build for
REM sm_89 alone cost a 3060 owner the GPU path in exactly that way.
REM
REM Turing (sm_75), Ampere (sm_86) and Ada (sm_89) are compiled in; the trailing
REM compute_89 embeds PTX so a card newer than any of them JITs instead of failing.

setlocal
call "%ProgramFiles%\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
if errorlevel 1 call "%ProgramFiles(x86)%\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1

nvcc -O3 -fmad=false -Wno-deprecated-gpu-targets ^
  -gencode arch=compute_75,code=sm_75 ^
  -gencode arch=compute_86,code=sm_86 ^
  -gencode arch=compute_89,code=sm_89 ^
  -gencode arch=compute_89,code=compute_89 ^
  -o "%~dp0find_targets.exe" "%~dp0find_targets.cu"

if errorlevel 1 (
  echo build failed
  exit /b 1
)

REM Also refresh the copy that ships inside the jar. Users get the fast path from the jar
REM alone and never run this script; forgetting to copy would leave the jar shipping a
REM stale kernel, which has already happened once and cost a real find. BundledKernelTest
REM fails the Maven build if this copy is older than the source.
copy /y "%~dp0find_targets.exe" "%~dp0..\src\main\resources\cuda\find_targets.exe" >nul
if errorlevel 1 (
  echo could not refresh src\main\resources\cuda\find_targets.exe
  exit /b 1
)

echo built %~dp0find_targets.exe and refreshed the bundled copy
endlocal
