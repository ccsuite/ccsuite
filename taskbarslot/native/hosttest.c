// Test host for the slot DLL, no Explorer needed: loads the DLL BUILT FOR THE SCRATCH KEYS (build-slot.cmd <file>) by path, gives the band a fake site window and drives it the way a rebar does.
// It writes only Software\ccsuite\taskbar-test (never the real keys) and checks, for several card counts: the band window's class and text, GetBandInfo's length,
// and that the relayout message makes the band read the registry again and ask the site for its size again (IOleCommandTarget::Exec, DBID_BANDINFOCHANGED).
// Build and run: hosttest.cmd        Exit code 0 means every check passed.
#define WINVER 0x0A00
#define _WIN32_WINNT 0x0A00
#include <initguid.h>
#include <windows.h>
#include <shlobj.h>
#include <stdio.h>
#include <string.h>
#include <wchar.h>

DEFINE_GUID(CLSID_CcuSlot, 0x8CB51477, 0xF1FA, 0x4633, 0xAD, 0xEB, 0x31, 0xF7, 0xFB, 0x41, 0x44, 0x52);

#define ROOT L"Software\\ccsuite\\taskbar-test"
#define CARD_H 44
#define CARD_W 101

typedef struct { IOleWindow ow; IOleCommandTarget ct; LONG ref; HWND hwnd; int changed; } Site;
#define OFCT(t) ((Site *)((char *)(t) - offsetof(Site, ct)))

static HRESULT STDMETHODCALLTYPE sQI(IOleWindow *t, REFIID r, void **p) {
    Site *s = (Site *)t;
    *p = NULL;
    if (IsEqualIID(r, &IID_IUnknown) || IsEqualIID(r, &IID_IOleWindow)) *p = &s->ow;
    else if (IsEqualIID(r, &IID_IOleCommandTarget)) *p = &s->ct;
    else return E_NOINTERFACE;
    InterlockedIncrement(&s->ref);
    return S_OK;
}
static ULONG STDMETHODCALLTYPE sAdd(IOleWindow *t) { return (ULONG)InterlockedIncrement(&((Site *)t)->ref); }
static ULONG STDMETHODCALLTYPE sRel(IOleWindow *t) { return (ULONG)InterlockedDecrement(&((Site *)t)->ref); }
static HRESULT STDMETHODCALLTYPE sWin(IOleWindow *t, HWND *h) { *h = ((Site *)t)->hwnd; return S_OK; }
static HRESULT STDMETHODCALLTYPE sHelp(IOleWindow *t, BOOL f) { return E_NOTIMPL; }
static IOleWindowVtbl siteVt = {sQI, sAdd, sRel, sWin, sHelp};

static HRESULT STDMETHODCALLTYPE cQI(IOleCommandTarget *t, REFIID r, void **p) { return sQI(&OFCT(t)->ow, r, p); }
static ULONG STDMETHODCALLTYPE cAdd(IOleCommandTarget *t) { return sAdd(&OFCT(t)->ow); }
static ULONG STDMETHODCALLTYPE cRel(IOleCommandTarget *t) { return sRel(&OFCT(t)->ow); }
static HRESULT STDMETHODCALLTYPE cStatus(IOleCommandTarget *t, const GUID *g, ULONG n, OLECMD *c, OLECMDTEXT *x) { return E_NOTIMPL; }
static HRESULT STDMETHODCALLTYPE cExec(IOleCommandTarget *t, const GUID *g, DWORD id, DWORD opt, VARIANT *in, VARIANT *out) {
    if (g && IsEqualGUID(g, &CGID_DeskBand) && id == DBID_BANDINFOCHANGED) OFCT(t)->changed++;
    return S_OK;
}
static IOleCommandTargetVtbl ctVt = {cQI, cAdd, cRel, cStatus, cExec};

static int failures;
static void check(int ok, const char *what, long got, long want) {
    if (!ok) { failures++; printf("FAIL %s: got %ld, wanted %ld\n", what, got, want); } else printf("ok   %s (%ld)\n", what, got);
}

static void configure(int legacy, int cards) {
    HKEY k;
    RegDeleteTreeW(HKEY_CURRENT_USER, ROOT);
    if (legacy) {
        DWORD v = (DWORD)legacy;
        RegCreateKeyExW(HKEY_CURRENT_USER, ROOT L"\\legacy", 0, NULL, 0, KEY_WRITE, NULL, &k, NULL);
        RegSetValueExW(k, L"slotCards", 0, REG_DWORD, (const BYTE *)&v, sizeof v);
        RegCloseKey(k);
    }
    for (int i = 0; i < cards; i++) {
        wchar_t sub[100];
        swprintf(sub, 100, ROOT L"\\cards\\card%d", i);
        RegCreateKeyExW(HKEY_CURRENT_USER, sub, 0, NULL, 0, KEY_WRITE, NULL, &k, NULL);
        RegSetValueExW(k, L"title", 0, REG_SZ, (const BYTE *)L"t", 4);
        RegCloseKey(k);
    }
}

static long bandLength(IDeskBand *band, int vertical) {
    DESKBANDINFO di = {0};
    di.dwMask = DBIM_MINSIZE | DBIM_ACTUAL;
    band->lpVtbl->GetBandInfo(band, 1, vertical ? DBIF_VIEWMODE_VERTICAL : DBIF_VIEWMODE_NORMAL, &di);
    return di.ptMinSize.x;
}

static void expect(HWND child, HWND host, IDeskBand *band, int legacy, int total, const char *label) {
    wchar_t cls[64], text[64], wantCls[64], wantText[32];
    char msg[160];
    GetClassNameW(child, cls, 64);
    GetWindowTextW(child, text, 64);
    if (total <= 1) swprintf(wantCls, 64, L"CcuSlotWnd"); else swprintf(wantCls, 64, L"CcuSlotWnd%d", total);
    swprintf(wantText, 32, L"%d:%d", legacy, total);
    snprintf(msg, sizeof msg, "%s: class %ls", label, wantCls);
    check(!wcscmp(cls, wantCls), msg, (long)wcslen(cls), (long)wcslen(wantCls));
    snprintf(msg, sizeof msg, "%s: text %ls", label, wantText);
    check(!wcscmp(text, wantText), msg, (long)wcslen(text), (long)wcslen(wantText));
    RECT r;
    GetClientRect(host, &r);
    int vertical = r.bottom > r.right;
    double s = GetDpiForWindow(host) / 96.0;
    long want = (long)(int)((vertical ? CARD_H : CARD_W) * s) * total;
    snprintf(msg, sizeof msg, "%s: %s length for %d cards", label, vertical ? "vertical" : "horizontal", total);
    check(bandLength(band, vertical) == want, msg, bandLength(band, vertical), want);
}

static void oneCase(IClassFactory *cf, HWND host, int legacy, int cards, int total, const char *label) {
    IDeskBand *band = NULL;
    IObjectWithSite *ows = NULL;
    configure(legacy, cards);
    if (FAILED(cf->lpVtbl->CreateInstance(cf, NULL, &IID_IDeskBand, (void **)&band)) || FAILED(band->lpVtbl->QueryInterface(band, &IID_IObjectWithSite, (void **)&ows))) { failures++; printf("FAIL %s: no band\n", label); return; }
    Site site = {{&siteVt}, {&ctVt}, 1, host, 0};
    check(SUCCEEDED(ows->lpVtbl->SetSite(ows, (IUnknown *)&site)), "SetSite", 0, 0);
    HWND child = NULL;
    band->lpVtbl->GetWindow(band, &child);
    expect(child, host, band, legacy ? legacy : 1, total, label);
    ows->lpVtbl->SetSite(ows, NULL);
    ows->lpVtbl->Release(ows);
    band->lpVtbl->Release(band);
}

static void relayoutCase(IClassFactory *cf, HWND host) {
    IDeskBand *band = NULL;
    IObjectWithSite *ows = NULL;
    configure(1, 0);
    cf->lpVtbl->CreateInstance(cf, NULL, &IID_IDeskBand, (void **)&band);
    band->lpVtbl->QueryInterface(band, &IID_IObjectWithSite, (void **)&ows);
    Site site = {{&siteVt}, {&ctVt}, 1, host, 0};
    ows->lpVtbl->SetSite(ows, (IUnknown *)&site);
    HWND child = NULL;
    band->lpVtbl->GetWindow(band, &child);
    UINT msg = RegisterWindowMessageW(L"ccsuite.taskbar.relayout");
    long before = bandLength(band, 0);
    SendMessageW(child, msg, 0, 0);
    check(site.changed == 0, "relayout with nothing changed asks nothing", site.changed, 0);
    configure(1, 2);
    SendMessageW(child, msg, 0, 0);
    check(site.changed == 1, "relayout after two cards registered asks the site once", site.changed, 1);
    check(bandLength(band, 0) == before * 3, "band is three cards long after the relayout", bandLength(band, 0), before * 3);
    wchar_t text[32];
    GetWindowTextW(child, text, 32);
    check(!wcscmp(text, L"1:3"), "text says 1:3 after the relayout", (long)wcslen(text), 3);
    SendMessageW(child, msg, 0, 0);
    check(site.changed == 1, "a second relayout with nothing new asks nothing", site.changed, 1);
    ows->lpVtbl->SetSite(ows, NULL);
    ows->lpVtbl->Release(ows);
    band->lpVtbl->Release(band);
}

int main(int argc, char **argv) {
    const char *dll = argc > 1 ? argv[1] : "ccuslot-test.dll";
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    CoInitialize(NULL);
    HMODULE lib = LoadLibraryA(dll);
    if (!lib) { printf("LoadLibrary %s failed (%lu)\n", dll, GetLastError()); return 2; }
    HRESULT (WINAPI *getCls)(REFCLSID, REFIID, void **) = (void *)GetProcAddress(lib, "DllGetClassObject");
    IClassFactory *cf = NULL;
    if (!getCls || FAILED(getCls(&CLSID_CcuSlot, &IID_IClassFactory, (void **)&cf))) { printf("no class factory\n"); return 2; }
    WNDCLASSW wc = {0};
    wc.lpfnWndProc = DefWindowProcW;
    wc.hInstance = GetModuleHandleW(NULL);
    wc.lpszClassName = L"SlotHostTest";
    RegisterClassW(&wc);
    for (int shape = 0; shape < 2; shape++) {   // a vertical taskbar (taller than wide), then a horizontal one
        HWND host = CreateWindowExW(WS_EX_TOOLWINDOW, wc.lpszClassName, L"", WS_POPUP, 0, 0, shape ? 700 : 152, shape ? 44 : 700, NULL, NULL, wc.hInstance, NULL);
        oneCase(cf, host, 0, 0, 1, "nothing set: one card");
        oneCase(cf, host, 1, 0, 1, "legacy 1");
        oneCase(cf, host, 2, 0, 2, "legacy 2");
        oneCase(cf, host, 1, 1, 2, "legacy 1 + 1 registered");
        oneCase(cf, host, 1, 2, 3, "legacy 1 + 2 registered");
        oneCase(cf, host, 2, 3, 5, "legacy 2 + 3 registered");
        oneCase(cf, host, 2, 40, 16, "clamped to 16");
        relayoutCase(cf, host);
        DestroyWindow(host);
    }
    RegDeleteTreeW(HKEY_CURRENT_USER, ROOT);
    cf->lpVtbl->Release(cf);
    HRESULT (WINAPI *canUnload)(void) = (void *)GetProcAddress(lib, "DllCanUnloadNow");
    check(canUnload && canUnload() == S_OK, "DllCanUnloadNow: nothing leaked", canUnload ? (long)canUnload() : -1, 0);
    FreeLibrary(lib);
    CoUninitialize();
    printf(failures ? "%d CHECK(S) FAILED\n" : "ALL CHECKS PASSED\n", failures);
    return failures ? 1 : 0;
}
