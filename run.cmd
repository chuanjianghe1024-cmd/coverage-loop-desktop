@echo off
rem Coverage Loop Desktop 启动脚本（需 JDK 17+，JAVA_HOME 指向本机 JDK）
setlocal
if "%JAVA_HOME%"=="" (
  echo [ERROR] 请先设置 JAVA_HOME 指向 JDK 17 及以上
  exit /b 1
)
call mvn -q javafx:run
endlocal
