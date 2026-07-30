@echo off
REM =====================================================================
REM  Minecraft Bingo - preparation de l'environnement de build (Windows)
REM
REM  1. Telecharge gradle-wrapper.jar (non versionne dans le depot)
REM  2. Verifie la presence d'un JDK 17
REM  3. Lance une resolution des dependances pour valider la config
REM =====================================================================
setlocal

cd /d "%~dp0"

set GRADLE_TAG=v8.8.0
set WRAPPER_JAR=gradle\wrapper\gradle-wrapper.jar
set WRAPPER_URL=https://raw.githubusercontent.com/gradle/gradle/%GRADLE_TAG%/gradle/wrapper/gradle-wrapper.jar

echo.
echo === [1/3] Gradle Wrapper ===
if not exist "gradle\wrapper" mkdir "gradle\wrapper"

if exist "%WRAPPER_JAR%" (
    echo [=] %WRAPPER_JAR% deja present.
) else (
    echo [^>] Telechargement depuis GitHub ^(Gradle %GRADLE_TAG%^)...
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
      "$ProgressPreference='SilentlyContinue'; try { Invoke-WebRequest -Uri '%WRAPPER_URL%' -OutFile '%WRAPPER_JAR%' -UseBasicParsing; exit 0 } catch { Write-Host $_.Exception.Message; exit 1 }"
    if errorlevel 1 (
        echo.
        echo [!] Echec du telechargement.
        echo     Solution de repli : installe Gradle 8.8 manuellement puis lance
        echo     "gradle wrapper --gradle-version 8.8" a la racine du projet.
        goto :fail
    )
    echo [+] %WRAPPER_JAR% installe.
)

echo.
echo === [2/3] Verification du JDK ===
where java >nul 2>&1
if errorlevel 1 (
    echo [!] Aucun "java" dans le PATH.
    echo     Installe un JDK 17 ^(Temurin / Microsoft Build of OpenJDK^) puis relance.
    goto :fail
)
java -version 2>&1 | findstr /i "version"
echo [i] Minecraft 1.20.1 requiert un JDK 17. Si tu vois 21+ ou 11, ajuste JAVA_HOME
echo     ou configure le "Gradle JVM" sur 17 dans ton IDE.

echo.
echo === [3/3] Validation de la configuration Gradle ===
call gradlew.bat --version
if errorlevel 1 goto :fail

call gradlew.bat dependencies --configuration modImplementation
if errorlevel 1 (
    echo [!] La resolution des dependances a echoue - verifie ta connexion.
    goto :fail
)

echo.
echo ==========================================================
echo  [OK] Environnement pret.
echo  Prochaines etapes :
echo    gradlew.bat build       - compile le mod
echo    gradlew.bat runClient   - lance un client de dev
echo    gradlew.bat runServer   - lance un serveur de dev
echo ==========================================================
endlocal
exit /b 0

:fail
echo.
echo [ECHEC] Setup interrompu.
endlocal
exit /b 1
