@echo off
REM ============================================================================
REM pclaude - Claude Code through ccacc's PAID lane (127.0.0.1:8681): the paid accounts, every model.
REM
REM It is the same Claude Code with the same configuration as claude.exe (CLAUDE.md and what it imports, skills, hooks, settings, history, memory):
REM only the endpoint differs, and the settings are given to this one process as environment variables, so claude.exe is not touched.
REM
REM Usage:   pclaude [claude arguments]
REM Options: CCACC_PCLAUDE_PORT   another port            CCACC_JOB   tag this run: ccacc calls --job NAME
REM The lanes must be on in ccacc (ccacc lanes on).
REM ============================================================================
setlocal
if not defined CCACC_PCLAUDE_PORT set "CCACC_PCLAUDE_PORT=8681"

where claude >nul 2>&1 || (echo [ERROR] claude is not on the PATH.& exit /b 1)
curl -s -m 3 -o nul "http://127.0.0.1:%CCACC_PCLAUDE_PORT%/v1/models" || (echo [ERROR] nothing answers on 127.0.0.1:%CCACC_PCLAUDE_PORT%: start ccacc and switch the lanes on with: ccacc lanes on.& exit /b 1)

set "ANTHROPIC_BASE_URL=http://127.0.0.1:%CCACC_PCLAUDE_PORT%"
set "ANTHROPIC_AUTH_TOKEN=ccacc-local"
if defined CCACC_JOB set "ANTHROPIC_CUSTOM_HEADERS=x-ccacc-job: %CCACC_JOB%"

claude %*
