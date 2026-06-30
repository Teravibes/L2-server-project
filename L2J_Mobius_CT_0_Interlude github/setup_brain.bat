@echo off
REM ===========================================================================
REM  One-step setup + launch for the FPC brain - local LLM via Ollama.
REM
REM  What it does:
REM    1. Installs Ollama if it is not already installed.
REM       - Uses bundled OllamaSetup.exe if present.
REM       - Else tries winget.
REM       - Else downloads OllamaSetup.exe directly.
REM    2. Makes sure the Ollama server is running.
REM    3. Pulls the chat model, default llama3.1.
REM    4. Creates a Python virtualenv and installs requirements.
REM    5. Writes a local .env file if one does not exist.
REM    6. Starts fpc_brain.py.
REM
REM  Usage:
REM    setup_brain.bat
REM    set OLLAMA_MODEL=gemma3 & setup_brain.bat
REM ===========================================================================

setlocal enabledelayedexpansion
cd /d "%~dp0"

if "%OLLAMA_MODEL%"=="" set "OLLAMA_MODEL=gemma3:12b"

echo.
echo ==^> FPC brain setup - model: %OLLAMA_MODEL%
echo ==^> Working folder:
cd
echo.

REM --- Basic project file checks --------------------------------------------

if not exist fpc_brain.py (
    echo ERROR: fpc_brain.py not found in this folder.
    echo Put setup_brain.bat in the same folder as fpc_brain.py.
    pause
    exit /b 1
)

if not exist requirements.txt (
    echo ERROR: requirements.txt not found in this folder.
    echo.
    echo Create a file named requirements.txt with these lines:
    echo flask
    echo openai
    echo python-dotenv
    echo.
    pause
    exit /b 1
)

REM --- 1. Ollama -------------------------------------------------------------

REM Try common Ollama install paths first, in case PATH is not refreshed.
if exist "%LOCALAPPDATA%\Programs\Ollama\ollama.exe" (
    set "PATH=%LOCALAPPDATA%\Programs\Ollama;%PATH%"
)

if exist "%ProgramFiles%\Ollama\ollama.exe" (
    set "PATH=%ProgramFiles%\Ollama;%PATH%"
)

where ollama >nul 2>&1
if errorlevel 1 (
    echo ==^> Ollama not found.

    if exist "%~dp0OllamaSetup.exe" (
        echo ==^> Installing bundled OllamaSetup.exe...
        start /wait "" "%~dp0OllamaSetup.exe"
    ) else (
        where winget >nul 2>&1
        if errorlevel 1 (
            echo ==^> winget not available. Downloading Ollama installer directly...

            if not exist "%TEMP%\fpc_brain_setup" mkdir "%TEMP%\fpc_brain_setup"

            powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $url='https://ollama.com/download/OllamaSetup.exe'; $out=Join-Path $env:TEMP 'fpc_brain_setup\OllamaSetup.exe'; Invoke-WebRequest -Uri $url -OutFile $out"

            if errorlevel 1 (
                echo ERROR: Could not download Ollama installer.
                echo Check internet connection or download OllamaSetup.exe manually.
                pause
                exit /b 1
            )

            echo ==^> Running downloaded Ollama installer...
            start /wait "" "%TEMP%\fpc_brain_setup\OllamaSetup.exe"
        ) else (
            echo ==^> Installing Ollama via winget...
            winget install --id Ollama.Ollama -e --accept-package-agreements --accept-source-agreements

            if errorlevel 1 (
                echo WARNING: winget install failed. Trying direct download instead...

                if not exist "%TEMP%\fpc_brain_setup" mkdir "%TEMP%\fpc_brain_setup"

                powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $url='https://ollama.com/download/OllamaSetup.exe'; $out=Join-Path $env:TEMP 'fpc_brain_setup\OllamaSetup.exe'; Invoke-WebRequest -Uri $url -OutFile $out"

                if errorlevel 1 (
                    echo ERROR: Could not download Ollama installer.
                    pause
                    exit /b 1
                )

                echo ==^> Running downloaded Ollama installer...
                start /wait "" "%TEMP%\fpc_brain_setup\OllamaSetup.exe"
            )
        )
    )

    REM Refresh PATH after installer.
    if exist "%LOCALAPPDATA%\Programs\Ollama\ollama.exe" (
        set "PATH=%LOCALAPPDATA%\Programs\Ollama;%PATH%"
    )

    if exist "%ProgramFiles%\Ollama\ollama.exe" (
        set "PATH=%ProgramFiles%\Ollama;%PATH%"
    )

    where ollama >nul 2>&1
    if errorlevel 1 (
        echo ERROR: Ollama installer finished, but ollama.exe is still not available.
        echo Close this Command Prompt window, open a new one, and run this BAT again.
        pause
        exit /b 1
    )
) else (
    echo ==^> Ollama already installed.
)

REM --- 2. Make sure the Ollama server is up ---------------------------------

curl -fsS http://127.0.0.1:11434/api/tags >nul 2>&1
if errorlevel 1 (
    echo ==^> Starting Ollama server in the background...
    start "" /b ollama serve

    for /l %%i in (1,1,30) do (
        timeout /t 1 /nobreak >nul
        curl -fsS http://127.0.0.1:11434/api/tags >nul 2>&1
        if not errorlevel 1 goto ollama_up
    )
)

:ollama_up
curl -fsS http://127.0.0.1:11434/api/tags >nul 2>&1
if errorlevel 1 (
    echo ERROR: Ollama server did not come up.
    echo Try closing this window, opening a new Command Prompt, and running this BAT again.
    pause
    exit /b 1
)

echo ==^> Ollama server is up.

REM --- 3. Pull the model -----------------------------------------------------

echo ==^> Pulling model "%OLLAMA_MODEL%". First run may download several GB.
ollama pull %OLLAMA_MODEL%

if errorlevel 1 (
    echo ERROR: Failed to pull the model "%OLLAMA_MODEL%".
    pause
    exit /b 1
)

echo ==^> Ollama model is ready.

REM --- 4. Python env + deps --------------------------------------------------

where python >nul 2>&1
if errorlevel 1 (
    echo ERROR: Python not found on PATH.
    echo Install Python from python.org and tick Add python.exe to PATH.
    echo Then close this window and run this BAT again.
    pause
    exit /b 1
)

python --version
if errorlevel 1 (
    echo ERROR: Python command exists but failed to run.
    pause
    exit /b 1
)

if not exist .venv (
    echo ==^> Creating Python virtualenv .venv...
    python -m venv .venv

    if errorlevel 1 (
        echo ERROR: Could not create Python virtualenv.
        echo Check that Python is installed correctly.
        pause
        exit /b 1
    )
) else (
    echo ==^> Python virtualenv already exists.
)

call .venv\Scripts\activate.bat

if errorlevel 1 (
    echo ERROR: Failed to activate Python virtualenv.
    pause
    exit /b 1
)

echo ==^> Installing Python requirements...
python -m pip install --upgrade pip

if errorlevel 1 (
    echo ERROR: Failed to upgrade pip.
    pause
    exit /b 1
)

python -m pip install -r requirements.txt

if errorlevel 1 (
    echo ERROR: Failed to install Python requirements.
    pause
    exit /b 1
)

REM --- 5. Local .env ---------------------------------------------------------

if not exist .env (
    echo ==^> Writing local .env file...
    (
        echo PROVIDER=ollama
        echo OLLAMA_MODEL=%OLLAMA_MODEL%
        echo DEEPSEEK_API_KEY=
    ) > .env
) else (
    echo ==^> Keeping existing .env file.
)

REM --- 6. Launch -------------------------------------------------------------

echo.
echo ==^> Starting the FPC brain on http://127.0.0.1:5000 ...
echo Press CTRL+C to stop it.
echo.

python fpc_brain.py

echo.
echo FPC brain exited with code %errorlevel%.
pause

endlocal