@echo off
REM Builds the chain filter for several architectures at once.
REM
REM Multi-architecture on purpose. A binary built with a single -arch will not load on any
REM other card, and the Java side cannot tell that apart from having no GPU at all -- it
REM reports "no usable GPU" and quietly runs about 4.5x slower on the CPU. A build for
REM sm_89 alone cost a 3060 owner the GPU path in exactly that way.
REM
REM Maxwell (sm_52), Pascal (sm_61), Turing (sm_75), Ampere (sm_86) and Ada (sm_89) are
REM compiled in; the trailing compute_89 embeds PTX so a card newer than any of them JITs
REM instead of failing. Unused cubins are dead weight in the fatbin, not in the instruction
REM stream -- 6ai measured single-arch against multi-arch at 26.71s vs 25.55s with identical
REM accept counts -- so the only cost of reaching further back is file size.

setlocal
call "%ProgramFiles%\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
if errorlevel 1 call "%ProgramFiles(x86)%\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1

set ARCHS=-gencode arch=compute_52,code=sm_52 -gencode arch=compute_61,code=sm_61 -gencode arch=compute_75,code=sm_75 -gencode arch=compute_86,code=sm_86 -gencode arch=compute_89,code=sm_89 -gencode arch=compute_89,code=compute_89

nvcc -O3 -fmad=false -Wno-deprecated-gpu-targets %ARCHS% ^
  -o "%~dp0find_targets.exe" "%~dp0find_targets.cu"

if errorlevel 1 (
  echo build failed
  exit /b 1
)

REM -fmad=false is not optional here. nvcc contracts a*b+c into an FMA by default, which is
REM MORE accurate than Java's separate multiply and add and therefore a different double.
REM The noise kernel is required to be bit-for-bit identical to ColumnPerlin, so a build
REM without this flag is wrong even though it looks fine.
nvcc -O3 -fmad=false -Wno-deprecated-gpu-targets %ARCHS% ^
  -o "%~dp0noise_column.exe" "%~dp0noise_column.cu"

if errorlevel 1 (
  echo build failed
  exit /b 1
)

REM The two-chunk lift. Once the chain scan moved to the GPU the lift became the whole cost
REM of a crossfind run -- 6bc had ruled it out for the card when pairs were rare, which stopped
REM being true. Measured at 4.3x over 24 CPU threads.
nvcc -O3 -Wno-deprecated-gpu-targets %ARCHS% ^
  -o "%~dp0two_chunk_lift.exe" "%~dp0two_chunk_lift.cu"

if errorlevel 1 (
  echo build failed
  exit /b 1
)

REM The state enumerator. Constructs the states that yield a wanted y instead of scanning
REM decoration seeds for it: 415 confirmed chains/s at height 10 against the chain scan's ~107,
REM and every hit is a real chain where the scan's are a third real.
nvcc -O3 -Wno-deprecated-gpu-targets %ARCHS% ^
  -o "%~dp0stack_enum.exe" "%~dp0stack_enum.cu"

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
copy /y "%~dp0noise_column.exe" "%~dp0..\src\main\resources\cuda\noise_column.exe" >nul
if errorlevel 1 (
  echo could not refresh the bundled noise_column.exe
  exit /b 1
)
copy /y "%~dp0two_chunk_lift.exe" "%~dp0..\src\main\resources\cuda\two_chunk_lift.exe" >nul
if errorlevel 1 (
  echo could not refresh the bundled two_chunk_lift.exe
  exit /b 1
)

copy /y "%~dp0stack_enum.exe" "%~dp0..\src\main\resources\cuda\stack_enum.exe" >nul
if errorlevel 1 (
  echo could not refresh the bundled stack_enum.exe
  exit /b 1
)

echo built all four kernels and refreshed the bundled copies
endlocal
