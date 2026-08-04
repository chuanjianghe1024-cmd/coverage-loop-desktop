@echo off
rem Coverage Loop Desktop - dev launch (mvn javafx:run, module path)
rem Auto-detect JDK 17+: JAVA_HOME -> E:\tools\jdk-17* -> IntelliJ JBR
setlocal
call "%~dp0scripts\find-jdk.cmd"
if not defined JDK_HOME goto :nojdk
set "JAVA_HOME=%JDK_HOME%"
cd /d "%~dp0"
if exist "mvnw.cmd" goto :usewrapper
call mvn -q javafx:run
goto :done
:usewrapper
call mvnw.cmd -q javafx:run
goto :done
:nojdk
echo [ERROR] JDK 17+ not found. Set JAVA_HOME to a JDK 17+ install.
pause
:done
endlocal
