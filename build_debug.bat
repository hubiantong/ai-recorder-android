@echo off
set JAVA_HOME=C:\Program Files\Java\jdk-21.0.11
"%JAVA_HOME%\bin\java.exe" -Xmx2048m -Dfile.encoding=UTF-8 -Dorg.gradle.native=false -Dorg.gradle.daemon=false -Dorg.gradle.vfs.watch=false -jar gradle\wrapper\gradle-wrapper.jar assembleDebug --no-daemon