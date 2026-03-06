@echo off
cd /d "%~dp0"

echo Building plugin...
call gradlew.bat jar
if errorlevel 1 (
    echo Build failed!
    pause
    exit /b 1
)

echo Deploying to sideloaded-plugins...
copy /y "build\libs\deadman-1.0-SNAPSHOT.jar" "%userprofile%\.runelite\sideloaded-plugins\deadman.jar"

echo Launching custom RuneLite...
for %%f in ("C:\Users\cvern\code\runelite\runelite\runelite-client\build\libs\*-shaded.jar") do set JAR=%%f
if not defined JAR (
    echo No shaded jar found. Build RuneLite first.
    pause
    exit /b 1
)
java -ea -Drunelite.pluginhub.skipverify=true -Drunelite.pluginhub.version=1.12.16 -jar "%JAR%" --developer-mode
pause
