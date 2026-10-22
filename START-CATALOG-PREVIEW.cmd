@echo off
setlocal
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo Install Node.js 22 or newer, then open this launcher again.
  pause
  exit /b 1
)
node build.mjs --preview
if errorlevel 1 (
  echo Preview build failed. Nothing was deployed.
  pause
  exit /b 1
)
node serve.mjs
if errorlevel 1 (
  echo Preview stopped with an error. Nothing was deployed.
  pause
  exit /b 1
)
endlocal
