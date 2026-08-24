@echo off
setlocal
rem TackShot AI 版 (V2.3-AI) launcher. Requires JDK/JRE 17+ (Copilot SDK requirement).
rem Layout A (ai folder): jar in dist\ ; Layout B (release folder): jar next to this script.
set "JAR=%~dp0dist\TackShotAI.jar"
if not exist "%JAR%" set "JAR=%~dp0TackShotAI.jar"
if not exist "%JAR%" (
  echo [TackShot-AI] TackShotAI.jar not found. Run build.sh first.
  pause
  exit /b 1
)

rem -- 定位 17+ 的 javaw：JAVA_HOME 优先（校验主版本），否则 PATH --
set "JAVAW="
if exist "%JAVA_HOME%\release" (
  for /f "tokens=2 delims=." %%v in ('type "%JAVA_HOME%\release" ^| findstr /r "^JAVA_VERSION"') do set "JVMAJOR=%%v"
)
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" if "%JVMAJOR%" GEQ "17" set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
if not defined JAVAW (
  where javaw >nul 2>nul && set "JAVAW=javaw"
)
if not defined JAVAW (
  echo [TackShot-AI] javaw.exe not found. Install JDK 17+ or set JAVA_HOME.
  pause
  exit /b 1
)

start "" "%JAVAW%" -Xmx192m -Dsun.java2d.uiScale.enabled=false -jar "%JAR%"
endlocal
