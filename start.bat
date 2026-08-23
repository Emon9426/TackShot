@echo off
setlocal
rem TackShot V2.0 (Java) launcher. Requires JDK/JRE 11+ (11 or 17 recommended).
rem Layout A (repo root): jar sits in dist\ ; Layout B (release folder): jar sits next to this script.
set "JAR=%~dp0TackShot.jar"
if not exist "%JAR%" set "JAR=%~dp0dist\TackShot.jar"
if not exist "%JAR%" (
  echo [TackShot] TackShot.jar not found next to this script nor in dist\ .
  echo [TackShot] Run build.sh first, or keep TackShot.jar + lib\ together with this script.
  pause
  exit /b 1
)
set "JAVAW="
where javaw >nul 2>nul && set "JAVAW=javaw"
if not defined JAVAW if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
if not defined JAVAW (
  echo [TackShot] javaw.exe not found in PATH and JAVA_HOME is not set.
  echo [TackShot] Install JDK 11/17, or edit this script to point to javaw.exe .
  pause
  exit /b 1
)
start "" "%JAVAW%" -Xmx128m -Dsun.java2d.uiScale.enabled=false -jar "%JAR%"
endlocal
