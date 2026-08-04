@echo off
rem Coverage Loop Desktop - 开发模式启动（Maven 插件方式，module path，无警告）
rem 需要 JDK 17+；使用项目自带 Maven Wrapper，无需全局安装 Maven
setlocal
if "%JAVA_HOME%"=="" (
  echo [ERROR] 请先设置 JAVA_HOME 指向 JDK 17 及以上
  pause
  exit /b 1
)
cd /d "%~dp0"
if exist "mvnw.cmd" (
  call mvnw.cmd -q javafx:run
) else (
  call mvn -q javafx:run
)
endlocal
