// fclaude-status  (no arguments): the status line of fclaude sessions ONLY (PRP 86 R4/R7). It is named in fclaude-settings.json (passed with --settings to fclaude sessions and to nothing
//                                  else); the global ccstatus.ts and ~/.claude/settings.json are not touched, so a paid or plain claude session never runs this code.
// Claude Code runs it and pipes the session JSON in; it prints one line: the base line the global ccstatus prints (the summary the session stored with `ccstatus set`, else the folder,
// then the model) plus the lane part when the free lane wrote a fresh status file for this session (ccacc, ~/ccsuite/ccacc/session-status/<session id>.txt): the figure against its close
// point, the account's room and the account's short name. Any error, a missing, stale or broken file: only the base line. It never throws and always exits 0.
import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";

const HOME = homedir().replaceAll("\\", "/");
const SUMMARY_DIR = process.env.CCSTATUS_SUMMARY_DIR ?? HOME + "/.claude/status";
const LANE_DIR = process.env.CCACC_SESSION_STATUS_DIR ?? HOME + "/ccsuite/ccacc/session-status";
const FRESH_MS = 6 * 3600e3;
const SAFE_ID = /^[A-Za-z0-9_-]{1,100}$/;

/** The lane's part of the line, or "" when there is nothing fresh and well formed to show. Never throws. */
function laneStatus(sid: string): string {
  try {
    if (!SAFE_ID.test(sid)) return "";
    const path = `${LANE_DIR}/${sid}.txt`;
    if (!existsSync(path)) return "";
    const kv: Record<string, string> = {};
    for (const line of readFileSync(path, "utf8").split("\n")) { const i = line.indexOf("="); if (i > 0) kv[line.slice(0, i)] = line.slice(i + 1).trim(); }
    const at = Date.parse(kv.at ?? "");
    const tokens = Number(kv.tokens), warn = Number(kv.warn), close = Number(kv.close), room = Number(kv.room);
    if (!Number.isFinite(at) || Date.now() - at > FRESH_MS || ![tokens, warn, close, room].every(Number.isFinite) || close <= 0) return "";
    const k = (n: number) => Math.round(n / 1000) + "K";
    const colour = tokens >= close ? "\x1b[31m" : tokens >= warn ? "\x1b[33m" : "";
    const figure = `${k(tokens)} of ${k(close)} close · room ${Math.round(room)}%`;
    const shown = colour ? `${colour}${figure}\x1b[0m` : figure;
    const account = kv.account && kv.account !== "-" ? kv.account : "";
    return [shown, account].filter(Boolean).join("  |  ");
  } catch { return ""; }
}

/** The base line, exactly as the global ccstatus prints it. Never throws. */
function baseLine(info: any, sid: string): string {
  try {
    const model: string = info.model?.display_name ?? info.model?.id ?? "";
    const dir: string = String(info.workspace?.current_dir ?? info.cwd ?? "").replaceAll("\\", "/").split("/").filter(Boolean).pop() ?? "";
    let summary = "";
    try { if (SAFE_ID.test(sid) && existsSync(`${SUMMARY_DIR}/${sid}.txt`)) summary = readFileSync(`${SUMMARY_DIR}/${sid}.txt`, "utf8").split("\n")[0].trim(); } catch { /* none */ }
    return [summary || dir, model].filter(Boolean).join("  |  ");
  } catch { return ""; }
}

try {
  let info: any = {};
  try { info = JSON.parse(await Bun.stdin.text()); } catch { /* run by hand, no JSON */ }
  const sid: string = String(info?.session_id ?? "");
  console.log([baseLine(info ?? {}, sid), laneStatus(sid)].filter(Boolean).join("  |  "));
} catch { /* nothing to print */ }
process.exit(0);
