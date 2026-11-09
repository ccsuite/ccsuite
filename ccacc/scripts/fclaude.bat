@echo off
REM ============================================================================
REM fclaude - Claude Code through ccacc's FREE lane (127.0.0.1:8686): the free accounts, Haiku and Sonnet (Opus is mapped to Sonnet).
REM
REM The same Claude Code with the same settings and system prompts as claude.exe (CLAUDE.md and what it imports, skills, hooks, settings, history), given to this
REM one process through flags and environment variables, so claude.exe is not touched. What differs, and nothing else:
REM   - the endpoint and the model names (the free lane);
REM   - no MCP tools (the user's MCP servers are left out: their schemas are sent with every request, which costs on the free lane);
REM   - one line added to the system prompt saying what this is;
REM   - its own colour theme (green), so a session on the free lane is told apart at a glance.
REM
REM Usage:   fclaude [claude arguments]        (Sonnet by default; fclaude --model haiku for Haiku)
REM Options: CCACC_FCLAUDE_PORT   another port            CCACC_JOB   tag this run: ccacc calls --job NAME
REM The lanes must be on in ccacc (ccacc lanes on). The files fclaude-theme.json, fclaude-settings.json and fclaude-mcp.json sit next to this one.
REM ============================================================================
setlocal
if not defined CCACC_FCLAUDE_PORT set "CCACC_FCLAUDE_PORT=8686"

where claude >nul 2>&1 || (echo [ERROR] claude is not on the PATH.& exit /b 1)
curl -s -m 3 -o nul "http://127.0.0.1:%CCACC_FCLAUDE_PORT%/v1/models" || (echo [ERROR] nothing answers on 127.0.0.1:%CCACC_FCLAUDE_PORT%: start ccacc and switch the lanes on with: ccacc lanes on.& exit /b 1)

REM The theme is a file in Claude Code's own themes folder (it is the only place Claude Code reads a custom theme from); it is added once and never changed.
if not exist "%USERPROFILE%\.claude\themes" mkdir "%USERPROFILE%\.claude\themes"
if not exist "%USERPROFILE%\.claude\themes\fclaude.json" copy /y "%~dp0fclaude-theme.json" "%USERPROFILE%\.claude\themes\fclaude.json" >nul

set "ANTHROPIC_BASE_URL=http://127.0.0.1:%CCACC_FCLAUDE_PORT%"
set "ANTHROPIC_AUTH_TOKEN=ccacc-local"
set "ANTHROPIC_MODEL=claude-sonnet-5-5"
set "ANTHROPIC_DEFAULT_SONNET_MODEL=claude-sonnet-5-5"
set "ANTHROPIC_DEFAULT_HAIKU_MODEL=claude-haiku-5-5"
set "ANTHROPIC_DEFAULT_OPUS_MODEL=claude-sonnet-5-5"
set "ANTHROPIC_SMALL_FAST_MODEL=claude-haiku-5-5"
if defined CCACC_JOB set "ANTHROPIC_CUSTOM_HEADERS=x-ccacc-job: %CCACC_JOB%"

REM Compaction (PRP 86 R3): Claude Code's own compaction is only the backstop; the cycle is closed earlier by the warn and close zones (ccacc lanes zones). Claude Code fires at about 80 percent of this window,
REM so the window is hard / 0.8 (compactTokens, 425000 for the 340,000 hard zone): its fire point lies at hard, never below it. The number is the lane own (ccacc lanes limits); 425000 when ccacc cannot say.
set "CLAUDE_CODE_AUTO_COMPACT_WINDOW=425000"
for /f "tokens=2 delims==" %%t in ('ccacc lanes limits 2^>nul ^| findstr /b compactTokens') do set "CLAUDE_CODE_AUTO_COMPACT_WINDOW=%%t"

claude --settings "%~dp0fclaude-settings.json" --strict-mcp-config --mcp-config "%~dp0fclaude-mcp.json" --append-system-prompt "This is fclaude: Claude Code on a plan that does not support the Opus model." %*
