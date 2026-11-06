@echo off
chcp 65001 >nul
cd /d "%~dp0"
where node >nul 2>nul || (echo Установите Node.js LTS с https://nodejs.org и запустите этот файл снова. & pause & exit /b 1)
set /p ADMIN_PASSWORD=Пароль для кабинета (от 8 символов): 
node --disable-warning=ExperimentalWarning server.js
pause
