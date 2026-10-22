@echo off
setlocal
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo Install Node.js 22 or newer, then open this launcher again.
  pause
  exit /b 1
)
if not exist "node_modules\miniflare\package.json" (
  echo Local dependencies are missing. In this folder run:
  echo npm ci --ignore-scripts --no-audit --no-fund
  echo Then open this launcher again. No cloud login is needed.
  pause
  exit /b 1
)
node local-setup.mjs --synthetic
if errorlevel 1 (
  echo Setup stopped. Retain the .local-state folder; no data was deleted.
  pause
  exit /b 1
)
endlocal
