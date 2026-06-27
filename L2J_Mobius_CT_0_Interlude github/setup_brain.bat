@echo off
REM ===========================================================================
REM  One-step setup + launch for the FPC "brain" (local LLM via Ollama).
REM
REM  What it does (idempotent - safe to re-run):
REM    1. Installs Ollama if it isn't already installed (via winget).
REM    2. Makes sure the Ollama service is running.
REM    3. Pulls the chat model (default: llama3.1).
REM    4. Creates a Python virtualenv and installs requirements.
REM    5. Writes a local .env (PROVIDER=ollama) if one doesn't exist.
REM    6. Starts fpc_brain.py.
REM
REM  Usage:
REM    setup_brain.bat                  (default model llama3.1)
REM    set OLLAMA_MODEL=gemma3 ^& setup_brain.bat
REM ===========================================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

if "%OLLAMA_MODEL%"=="" set "OLLAMA_MODEL=llama3.1"

echo.
echo ==^> FPC brain setup (model: %OLLAMA_MODEL%)
echo.

REM --- 1. Ollama -------------------------------------------------------------
where ollama >nul 2>&1
if errorlevel 1 (
    echo ==^> Ollama not found. Installing via winget...
    winget install --id Ollama.Ollama -e --accept-package-agreements --accept-source-agreements
    if errorlevel 1 (
        echo [!] Automatic install failed. Please install Ollama from https://ollama.com/download and re-run.
        exit /b 1
    )
    echo [!] If 'ollama' is still not recognized, close and reopen this window, then re-run.
) else (
    echo ==^> Ollama already installed.
)

REM --- 2. Make sure the Ollama server is up ----------------------------------
curl -fsS http://127.0.0.1:11434/api/tags >nul 2>&1
if errorlevel 1 (
    echo ==^> Starting Ollama server in the background...
    start "" /b ollama serve
    REM give it a moment to come up
    for /l %%i in (1,1,30) do (
        timeout /t 1 /nobreak >nul
        curl -fsS http://127.0.0.1:11434/api/tags >nul 2>&1
        if not errorlevel 1 goto :ollama_up
    )
)
:ollama_up
curl -fsS http://127.0.0.1:11434/api/tags >nul 2>&1
if errorlevel 1 (
    echo [!] Ollama server did not come up. Open a new window and run "ollama serve" manually.
    exit /b 1
)
echo ==^> Ollama server is up.

REM --- 3. Pull the model -----------------------------------------------------
echo ==^> Pulling model "%OLLAMA_MODEL%" (first run downloads several GB)...
ollama pull %OLLAMA_MODEL%
if errorlevel 1 (
    echo [!] Failed to pull the model.
    exit /b 1
)

REM --- 4. Python env + deps --------------------------------------------------
if not exist .venv (
    echo ==^> Creating Python virtualenv (.venv)...
    python -m venv .venv
    if errorlevel 1 (
        echo [!] Could not create venv. Is Python installed and on PATH? https://www.python.org/downloads/
        exit /b 1
    )
)
call .venv\Scripts\activate.bat
echo ==^> Installing Python requirements...
python -m pip install --quiet --upgrade pip
python -m pip install --quiet -r requirements.txt

REM --- 5. Local .env ---------------------------------------------------------
if not exist .env (
    echo ==^> Writing a local .env (PROVIDER=ollama)...
    (
        echo PROVIDER=ollama
        echo OLLAMA_MODEL=%OLLAMA_MODEL%
        echo DEEPSEEK_API_KEY=
    ) > .env
) else (
    echo ==^> Keeping your existing .env (not overwriting).
)

REM --- 6. Launch -------------------------------------------------------------
echo ==^> Starting the FPC brain on http://127.0.0.1:5000 ...
python fpc_brain.py

endlocal
