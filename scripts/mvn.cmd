@echo off
rem Runs Maven with the JDK 21 this project requires. Usage: scripts\mvn.cmd verify
setlocal
rem Wire the repository's git hooks once (staged-files Spotless check on commit, .githooks/):
rem quiet, idempotent, never overrides a path the developer set themselves. Only in a clone: an
rem unpacked source archive has no .git, and git would otherwise write to an enclosing repository.
set "HOOKS_PATH=none"
if exist "%~dp0..\.git" set "HOOKS_PATH="
if "%HOOKS_PATH%"=="" for /f "delims=" %%h in ('git -C "%~dp0.." config --get core.hooksPath 2^>nul') do set "HOOKS_PATH=%%h"
if "%HOOKS_PATH%"=="" git -C "%~dp0.." config core.hooksPath .githooks >nul 2>&1
if "%JRSCTL_JDK%"=="" set "JRSCTL_JDK=C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot"
if not exist "%JRSCTL_JDK%\bin\java.exe" (
  echo JDK 21 not found at "%JRSCTL_JDK%". Set JRSCTL_JDK to a JDK 21 home. 1>&2
  exit /b 1
)
set "JAVA_HOME=%JRSCTL_JDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
if "%JRSCTL_MAVEN%"=="" (
  call "%~dp0..\mvnw.cmd" -B %*
) else (
  call "%JRSCTL_MAVEN%\bin\mvn.cmd" -B %*
)
exit /b %ERRORLEVEL%
