@echo off
rem Detect a usable JDK: JAVA_HOME -> E:\tools\jdk-17* -> IntelliJ JBR
rem Sets JDK_HOME when found; leaves it undefined otherwise.
set "JDK_HOME="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JDK_HOME=%JAVA_HOME%"
if not defined JDK_HOME if exist "E:\tools\jdk-17.0.20+8\bin\java.exe" set "JDK_HOME=E:\tools\jdk-17.0.20+8"
if not defined JDK_HOME for /d %%d in ("E:\tools\jdk-17*") do if exist "%%d\bin\java.exe" set "JDK_HOME=%%d"
if not defined JDK_HOME for /d %%d in ("C:\Program Files\Java\jdk-17*") do if exist "%%d\bin\java.exe" set "JDK_HOME=%%d"
if not defined JDK_HOME for /d %%d in ("C:\Users\%USERNAME%\.jdks\*") do if exist "%%d\bin\java.exe" set "JDK_HOME=%%d"
if not defined JDK_HOME for /d %%d in ("C:\Program Files\Eclipse Adoptium\jdk-17*") do if exist "%%d\bin\java.exe" set "JDK_HOME=%%d"
if not defined JDK_HOME if exist "C:\Users\%USERNAME%\AppData\Local\Programs\IntelliJ IDEA\jbr\bin\java.exe" set "JDK_HOME=C:\Users\%USERNAME%\AppData\Local\Programs\IntelliJ IDEA\jbr"
exit /b 0
