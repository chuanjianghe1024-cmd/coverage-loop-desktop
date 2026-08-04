@echo off
rem Coverage Loop Desktop - recommended launch (fat jar)
rem Auto-detect JDK 17+: JAVA_HOME -> E:\tools\jdk-17* -> IntelliJ JBR
setlocal
call "%~dp0scripts\find-jdk.cmd"
if not defined JDK_HOME goto :nojdk
set "JAVA_HOME=%JDK_HOME%"
cd /d "%~dp0"
if exist "target\coverage-loop-desktop-0.5.2.jar" goto :launch
echo [INFO] First run: building executable jar with maven...
if exist "mvnw.cmd" goto :usewrapper
call mvn -q package -DskipTests
goto :checkbuild
:usewrapper
call mvnw.cmd -q package -DskipTests
:checkbuild
if errorlevel 1 goto :buildfail
:launch
echo [INFO] Starting Coverage Loop Desktop ...
"%JAVA_HOME%\bin\java.exe" -jar "target\coverage-loop-desktop-0.5.2.jar"
goto :done
:nojdk
echo [ERROR] JDK 17+ not found. Set JAVA_HOME to a JDK 17+ install.
pause
goto :done
:buildfail
echo [ERROR] Build failed. Check network and Maven environment.
pause
:done
endlocal
