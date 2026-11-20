# taskbarslot: one shared band in the Windows taskbar, N cards from any tool

A deskband DLL (`native/slot.c`) reserves a strip in Explorer's taskbar; a tool puts its own JavaFX window (any top-level window with a known title) into one slot of it. ccacc's usage and free-account cards live there already; a tool such as `cgw` registers one more card. No dependency on usagetracker, base, webclient or any Claude code; Java 25, FFM, no JavaFX needed here.

## The contract (all a tool and the DLL share)

- Each card is a subkey `HKCU\Software\ccsuite\taskbar\cards\<id>` with SZ `title` (title of the window to host) and DWORD `order`.
- Band length in cards = ccacc's DWORD `HKCU\Software\ccsuite\ccacc\slotCards` (1 or 2, default 1) + the number of subkeys, at most 16.
- ccacc's cards keep slots 0..legacy-1. Registered cards follow, sorted by `order`, ties by id. The band always reserves ccacc's first slot, so with ccacc not running it shows as an empty dark slot before the first registered card (accepted until ccacc moves to registered cards).
- The DLL reads the registry only when the band is created (Explorer: toggle the band in the taskbar's Toolbars menu, or restart Explorer). It names the band window class `CcuSlotWnd` (1 card in all), `CcuSlotWnd2`, `CcuSlotWnd3` ... and its text `legacy:total` (for example `1:3`).

## API (package `ccsuite.taskbarslot`)

```java
SlotInstaller.install();                                  // once: extracts the DLL (named by hash) to ~/ccsuite/taskbarslot and regsvr32s it for the current user
var cards = Cards.live();
cards.register("cgw", "cgw-taskbar-card", 10);            // id, window title, order; takes effect when Explorer next creates the band
var slot = Slot.registered(cards, "cgw", "cgw-taskbar-card");
// every second, on the thread that owns the window:
switch (slot.tick()) {
    case WAITING -> {}                                    // no band yet, or not long enough to hold this card yet
    case EMBEDDED -> {}                                   // in its slot; kept sized to it
    case LOST -> recreateTheWindow();                     // the band is gone (Explorer restarted): the window died with it
}
slot.release();                                           // on exit: the window leaves the band
cards.unregister("cgw");                                  // the card is no longer wanted
Relayout.send();                                          // STRETCH, unproven live: asks the loaded band to read the registry again
```

`Cards.registered()` is cached for ten seconds (each read is a `reg.exe` process); `Cards.registeredNow()` reads again. `BandLayout.target(w, h, vertical, cards, index[, span])` is the pure placement: slots stacked down a vertical taskbar, side by side along a horizontal one.

## Build and test

- `native\build-slot.cmd` builds the DLL into `src/main/resources/ccsuite/taskbarslot/ccuslot.dll` (`gcc` from llvm-mingw, `-Wall -Wextra` clean). It never registers it.
- `native\hosttest.cmd` builds a copy that reads only `HKCU\Software\ccsuite\taskbar-test`, and drives it with a fake rebar site (`hosttest.c`): class, text and length for 1 to 16 cards on a vertical and a horizontal band, and the relayout message. `DllHostTest` runs it from `mvn test`.
- Registry tests use the scratch key `HKCU\Software\ccsuite\taskbar-test` only.
- The shared window procedure of the DLL runs inside `explorer.exe`: keep it tiny and stateless.
