@echo off
set PATH_TO_FX=C:\Java\javafx-sdk-21.0.4\lib
if not exist gradlew.bat (
  echo Gradle wrapper is not included in this source-only package.
  echo If Gradle is installed, run: gradle run
  echo Otherwise open the project in IntelliJ IDEA and run ClipForgeApp.
  pause
  exit /b 1
)
call gradlew.bat run
pause
