@echo off
rem Coverage Loop Desktop - 双击/命令行启动（推荐，fat jar 方式）
rem 需要 JDK 17+；首次运行自动用 Maven Wrapper 构建可执行 jar
setlocal
if "%JAVA_HOME%"=="" (
  echo [ERROR] 请先设置 JAVA_HOME 指向 JDK 17 及以上
  pause
  exit /b 1
)
cd /d "%~dp0"
if not exist "target\coverage-loop-desktop-0.5.2.jar" (
  echo [INFO] 首次运行，正在构建可执行 jar（mvn package -DskipTests）...
  if exist "mvnw.cmd" (
    call mvnw.cmd -q package -DskipTests
  ) else (
    call mvn -q package -DskipTests
  )
  if errorlevel 1 (
    echo [ERROR] 构建失败，请检查网络与 Maven 环境
    pause
    exit /b 1
  )
)
echo [INFO] 启动 Coverage Loop Desktop ...
java -jar "target\coverage-loop-desktop-0.5.2.jar"
endlocal
