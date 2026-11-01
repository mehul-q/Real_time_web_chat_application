@echo off
setlocal
cd /d "%~dp0\.."
echo Build gateway and web UI...
call mvn package
if errorlevel 1 goto failed
start "ChatServer" cmd /k "cd /d ""%CD%"" && java -cp target\classes ChatServer"
timeout /t 2 >nul
start "WebGateway" cmd /k "cd /d ""%CD%"" && mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=WebGateway"
start "WebFiles" cmd /k "cd /d ""%CD%"" && java -cp target\classes WebFiles"
echo Open http://localhost:8080
goto end
:failed
echo Build failed. Make sure ChatServer.java, Protocol.java, UserStore.java and ChatHistory.java are in src\main\java.
:end
pause
