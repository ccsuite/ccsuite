# ccsuite

Tools for people who use Claude Code with more than one Claude subscription. The main one is **ccacc**.

## ccacc

ccacc shows how much of each account's 5-hour and weekly quota is used, and moves Claude Code to the account with the most room, either when you ask or on its own. It sits in the tray (Windows) or the menu bar (macOS) and keeps out of the way.

![The ccacc window: every account with its 5-hour and weekly use, and the mode](docs/screenshots/accounts.png)

What it does:

- Shows the 5-hour use, the weekly use, the weekly pace (use divided by time gone: 100% spends the week exactly as it passes) and what is free, for every account, and the same added up over all of them.
- Switches Claude Code to another account in one click, or with `ccacc switch <id>`. A switch you make is respected: automatic rebalancing waits half an hour before moving you again.
- Has three modes. **Auto** moves Claude Code when an account runs out and rebalances toward the account with the most room. **Locked** never moves it, but still warns you when the account nears its limit. **Paused** does not even poll.
- Lets you give each account a limit (the share of its quota Claude Code may use), and mark an account **monitor only**: it is watched and counted in the totals, but never switched to.
- Windows only: a small card in the taskbar, one account at a time, with the active account's name glowing, red when you are spending too fast.
- Can run a local proxy on the machine (what `ccls` did). It is off until you turn it on with `ccacc part server on`.

![The taskbar card: four accounts and the all-accounts page](docs/screenshots/taskbar-card.png)

![Managing accounts: name, limit, monitor only](docs/screenshots/manage-accounts.png)

### Install

Windows, in cmd or PowerShell (`curl.exe` ships with Windows 10 and 11):

    curl.exe -fLo ccacc.exe https://github.com/ccsuite/ccsuite/releases/download/v0.1.0-beta.1/ccacc.exe
    .\ccacc.exe install --start-at-login

macOS, in Terminal (one line; `uname -m` picks Apple silicon or Intel):

    curl -fLo /tmp/ccacc https://github.com/ccsuite/ccsuite/releases/download/v0.1.0-beta.1/ccacc-macos-$(uname -m) && chmod +x /tmp/ccacc && /tmp/ccacc install --start-at-login

On a Mac with no Java 25, ccacc asks once, in the terminal, whether to download a Java runtime (Eclipse Temurin, about 50 MB); answer y. The Mac binary is signed ad hoc, not by an Apple developer. Downloaded with `curl` it opens as it is; downloaded with a browser, macOS asks once (System Settings, Privacy & Security, Open Anyway). The first time ccacc reads Claude Code's login from the Keychain, macOS asks whether to allow it: choose Always Allow.

ccacc is a single file. On first run it fetches its jar from the release, checked against the checksum it carries, and a Java runtime if there is none. Later, `ccacc update` brings it to the newest release.

### First use

ccacc needs to know your accounts and to hold a saved login for each, so that it can switch between them.

1. Add each account: `ccacc account add ann name=Ann email=ann@example.org trackUsage=on`
2. In Claude Code, log in as that account (`claude`, then `/login`).
3. Save that login for it: `ccacc register --id ann`
4. Repeat for every account. `ccacc status` says which one Claude Code is on, `ccacc usage` shows the numbers, and `ccacc` with no arguments starts the app.

`ccacc register` asks Anthropic whose tokens the login really holds and refuses a mixed-up one, so a saved login can never carry another account's tokens under the wrong name.

After a switch, restart Claude Code sessions that were already running. A session started before the switch keeps its old login in memory and can save it back over the new one when it refreshes its token. In Locked mode ccacc warns you when that happens.

### Commands

    ccacc                        the app: tray icon, account watcher, usage widget, local proxy
    ccacc status | list | usage  who Claude Code is on, the accounts, the usage of each
    ccacc switch [id]            switch Claude Code (no id: the next account)
    ccacc mode auto|locked|paused
    ccacc part watch|widget|server on|off
    ccacc account list|add|set|remove
    ccacc register [--id id]     save Claude Code's current login for an account
    ccacc update                 the newest release

Commands that change something are handed to the running app, so the app is the only writer of everything it holds. With no app running they change the files directly.

### Status

This is a beta.

- Windows: used every day by its author.
- macOS: built and its command line tested on a macOS machine in CI, but the tray icon, the windows and the Keychain access have not yet been tried by a person on a Mac. Claude Code keeps its login in the Keychain on macOS; ccacc reads and writes it there with the system `security` tool, and falls back to the credentials file when there is no Keychain item. Reports from Mac users are welcome.
- The taskbar card exists on Windows only.

ccacc only moves your own logins between your own Claude Code sessions on your own machine.

### Files

Everything lives under `~/ccsuite`: the accounts (`base/credentials.json`), the saved logins, the settings (`ccacc/settings.json`) and, while the app runs, the address its command line talks to (`ccacc/control.json`, readable by you only).

## The rest of the suite

ccacc is built from small parts that also run on their own: `ccu` (the usage widget), `cswap` (the account switcher), `ccls` (the local proxy) and `ccd` (downloads a Claude chat from claude.ai). Each module has its own folder and README. The modules that talk to claude.ai through a browser session are a fallback for when the usual channel is unavailable.

## Build from source

Java 25 and Maven. Not possible outside the author's machine yet: three small libraries of the same author that ccsuite uses (`cdpai-curlimpersonate`, `xyz-jphil-logback-converters`, `xyz-jphil-windows_console_set_unicode_output`) are not on Maven Central yet, so a clean checkout cannot resolve them. Once they are published, this is all it takes:

    mvn package -pl ccacc -am           the jar for the platform you build on, ccacc/target/ccacc.jar
    mvn -Pmac-aarch64 package -pl ccacc -am    a jar for Apple silicon (also -Pmac for Intel, -Pwin for Windows)

JavaFX carries its native libraries per platform, so there is one jar per platform; the profiles above choose which. The launcher executable is built by `jr-maven-plugin`; see `release.ps1`.

## Licence

Apache License 2.0, see `LICENSE`.
