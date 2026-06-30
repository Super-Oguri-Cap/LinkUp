@echo off
title LinkUp

:: ==========================================
:: Auto-detect Java (IntelliJ IDEA bundled JBR)
:: ==========================================
if "%JAVA_HOME%"=="" (
    if exist "E:\AAA_Code\JAVA\IDEA\IntelliJ IDEA 2025.3.3\jbr\bin\java.exe" set "JAVA_HOME=E:\AAA_Code\JAVA\IDEA\IntelliJ IDEA 2025.3.3\jbr"
)
if "%JAVA_HOME%"=="" (
    echo [ERROR] Java not found. Install JDK 17+ or set JAVA_HOME.
    echo Download: https://adoptium.net/
    pause
    exit /b 1
)
set "JAVA=%JAVA_HOME%\bin\java.exe"

:menu
cls
echo ============================================
echo            LinkUp Launcher
echo ============================================
echo.
echo  1. Build + Start Server + Start Client
echo  2. Build + Start Server
echo  3. Build + Start Client
echo  4. Build only
echo  5. Build + Package EXE
echo  6. Exit
echo.
set /p choice="Select (1-6): "

if "%choice%"=="1" goto :build_both
if "%choice%"=="2" goto :build_then_server
if "%choice%"=="3" goto :build_then_client
if "%choice%"=="4" goto :build_only
if "%choice%"=="5" goto :build_exe
if "%choice%"=="6" exit /b 0
goto :menu

:build_both
call :build
if %errorlevel% neq 0 pause & exit /b 1
start "LinkUp Server" "%JAVA%" -jar target\LinkUp-1.0-SNAPSHOT.jar server
start "LinkUp Client" "%JAVA%" -jar target\LinkUp-1.0-SNAPSHOT.jar client
goto :done

:build_then_server
call :build
if %errorlevel% neq 0 pause & exit /b 1
start "LinkUp Server" "%JAVA%" -jar target\LinkUp-1.0-SNAPSHOT.jar server
goto :done

:build_then_client
call :build
if %errorlevel% neq 0 pause & exit /b 1
start "LinkUp Client" "%JAVA%" -jar target\LinkUp-1.0-SNAPSHOT.jar client
goto :done

:build_only
call :build
goto :done

:build_exe
call :build
if %errorlevel% neq 0 pause & exit /b 1
echo [3/3] Generating EXE...
if not exist "%LAUNCH4J_HOME%\launch4jc.exe" (
    if exist "C:\Program Files\Launch4j\launch4jc.exe" (
        set "LAUNCH4J_HOME=C:\Program Files\Launch4j"
    ) else (
        echo [ERROR] Launch4j not found. Download from https://sourceforge.net/projects/launch4j/
        echo Install to default path, then retry.
        pause
        goto :done
    )
)
"%LAUNCH4J_HOME%\launch4jc.exe" launch4j_server.xml
"%LAUNCH4J_HOME%\launch4jc.exe" launch4j_client.xml
if exist target\LinkUpServer.exe (
    echo OK --^> target\LinkUpServer.exe
    echo OK --^> target\LinkUpClient.exe
) else (
    echo [ERROR] EXE generation failed
)
goto :done

:build
echo.
echo [1/2] Cleaning...
call mvnw.cmd clean -q
if %errorlevel% neq 0 echo [ERROR] Clean failed & exit /b 1

echo [2/2] Packaging...
call mvnw.cmd package -q -DskipTests
if %errorlevel% neq 0 echo [ERROR] Build failed & exit /b 1
echo.
echo Build OK --^> target\LinkUp-1.0-SNAPSHOT.jar
echo.
exit /b 0

:done
echo.
pause
goto :menu