// PRP 86 R4/R7: fclaude-status (the status line of fclaude sessions only, set in fclaude-settings.json) prints the same base line as the global ccstatus (stored summary or folder, model) and appends the figure against close, the room and the account when the free lane wrote a fresh status file for the session; any error or missing file: just the base line.
// Run: bun test ccacc/scripts/fclaude-status.test.ts   (FCLAUDE_STATUS_SCRIPT overrides the script path)
import { expect, test } from "bun:test";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const SCRIPT = process.env.FCLAUDE_STATUS_SCRIPT ?? import.meta.dir + "/fclaude-status.ts";

async function run(statusDir: string, info: object): Promise<string> {
  const p = Bun.spawn(["bun", SCRIPT], { stdin: new Blob([JSON.stringify(info)]), stdout: "pipe", env: { ...process.env, CCACC_SESSION_STATUS_DIR: statusDir } });
  return (await new Response(p.stdout).text()).replace(/\x1b\[[0-9;]*m/g, "").trim();
}

const info = (sid: string) => ({ session_id: sid, model: { display_name: "Sonnet 5.5" }, workspace: { current_dir: "C:/x/daily" } });
const file = (tokens: number, at = new Date().toISOString()) => `account=LEO\ntokens=${tokens}\nwarn=200000\nclose=250000\nhard=340000\nroom=63\nat=${at}\n`;

test("no status file: exactly the old line", async () => {
  const dir = mkdtempSync(join(tmpdir(), "ccst-"));
  expect(await run(dir, info("s-none"))).toBe("daily  |  Sonnet 5.5");
});

test("a fresh file: the figure against close, the room and the account", async () => {
  const dir = mkdtempSync(join(tmpdir(), "ccst-"));
  writeFileSync(join(dir, "s-1.txt"), file(132000));
  expect(await run(dir, info("s-1"))).toBe("daily  |  Sonnet 5.5  |  132K of 250K close · room 63%  |  LEO");
});

test("a stale file (a day old) is ignored", async () => {
  const dir = mkdtempSync(join(tmpdir(), "ccst-"));
  writeFileSync(join(dir, "s-2.txt"), file(132000, new Date(Date.now() - 26 * 3600e3).toISOString()));
  expect(await run(dir, info("s-2"))).toBe("daily  |  Sonnet 5.5");
});

test("a broken file never breaks the line", async () => {
  const dir = mkdtempSync(join(tmpdir(), "ccst-"));
  writeFileSync(join(dir, "s-3.txt"), "garbage\n");
  expect(await run(dir, info("s-3"))).toBe("daily  |  Sonnet 5.5");
});

test("at warn and at close the figure is coloured", async () => {
  const dir = mkdtempSync(join(tmpdir(), "ccst-"));
  writeFileSync(join(dir, "s-4.txt"), file(260000));
  const p = Bun.spawn(["bun", SCRIPT], { stdin: new Blob([JSON.stringify(info("s-4"))]), stdout: "pipe", env: { ...process.env, CCACC_SESSION_STATUS_DIR: dir } });
  expect(await new Response(p.stdout).text()).toContain("\x1b[31m");
});

test("the stored summary of the session comes first, as in the global ccstatus", async () => {
  const dir = mkdtempSync(join(tmpdir(), "ccst-"));
  const sumDir = mkdtempSync(join(tmpdir(), "ccsum-"));
  writeFileSync(join(sumDir, "s-5.txt"), "fixing the thing\n");
  writeFileSync(join(dir, "s-5.txt"), file(132000));
  const p = Bun.spawn(["bun", SCRIPT], { stdin: new Blob([JSON.stringify(info("s-5"))]), stdout: "pipe", env: { ...process.env, CCACC_SESSION_STATUS_DIR: dir, CCSTATUS_SUMMARY_DIR: sumDir } });
  expect((await new Response(p.stdout).text()).replace(/\x1b\[[0-9;]*m/g, "").trim()).toBe("fixing the thing  |  Sonnet 5.5  |  132K of 250K close · room 63%  |  LEO");
});

test("no JSON on stdin and a missing status folder: it still exits 0 and prints without throwing", async () => {
  const p = Bun.spawn(["bun", SCRIPT], { stdin: new Blob(["not json"]), stdout: "pipe", stderr: "pipe", env: { ...process.env, CCACC_SESSION_STATUS_DIR: join(tmpdir(), "no-such-folder-86") } });
  await new Response(p.stdout).text();
  expect(await p.exited).toBe(0);
});

test("a session id that is a path is never followed", async () => {
  const dir = mkdtempSync(join(tmpdir(), "ccst-"));
  expect(await run(dir, info("../../etc/x"))).toBe("daily  |  Sonnet 5.5");
});
