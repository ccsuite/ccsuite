// The CCU taskbar slot: a deskband that does nothing except reserve a slot of one card's height in the taskbar.
// All drawing and logic live in the Java side (ccsuite.taskbarslot), which parents a JavaFX window into this slot's
// window. This DLL runs inside explorer.exe, so it is deliberately tiny, holds no state and never needs to change.
// Build: build-slot.cmd   Register for the current user, no admin: regsvr32 /s ccuslot.dll
#define WINVER 0x0A00
#define _WIN32_WINNT 0x0A00
#include <initguid.h>
#include <windows.h>
#include <shlobj.h>
#include <stddef.h>
#include <stdlib.h>
#include <wchar.h>

#define BAND_NAME L"CCU taskbar card"
#define CLASS_BASE L"CcuSlotWnd"   // the window class of a band that is n cards long is CcuSlotWnd (one), CcuSlotWnd2, CcuSlotWnd3 ... so the Java side can tell what this DLL really gave the taskbar
#define MAX_CARDS 16
#define CATID_DESKBAND L"{00021492-0000-0000-C000-000000000046}"
#ifndef LEGACY_KEY
#define LEGACY_KEY L"Software\\ccsuite\\ccacc"            // ccacc's own DWORD slotCards: 1 or 2 cards, default 1
#endif
#ifndef CARDS_KEY
#define CARDS_KEY L"Software\\ccsuite\\taskbar\\cards"    // one subkey per card registered by another tool
#endif
#define CARD_H 44
#define CARD_W 101
DEFINE_GUID(CLSID_CcuSlot, 0x8CB51477, 0xF1FA, 0x4633, 0xAD, 0xEB, 0x31, 0xF7, 0xFB, 0x41, 0x44, 0x52);

typedef struct {
    IDeskBand band;
    IObjectWithSite site;
    IPersistStream persist;
    LONG ref;
    IUnknown *pSite;
    HWND parent, hwnd;
    int cards, legacy;   // the band's length (1..MAX_CARDS) and how many of those are ccacc's (1 or 2), read from the registry once when the band is created, and used for its size and its window class from then on
} Slot;

static HINSTANCE g_inst;
static LONG g_objects, g_locks;

static double scale(HWND h) {
    UINT d = h ? GetDpiForWindow(h) : 0;
    return (d ? d : 96) / 96.0;
}

static void relayout(Slot *b);

// The one thing the band window answers besides the default: the host's "ccsuite.taskbar.relayout" message, after a tool registered or unregistered a card.
static LRESULT CALLBACK slotProc(HWND h, UINT m, WPARAM w, LPARAM l) {
    static UINT relayoutMsg;
    if (!relayoutMsg) relayoutMsg = RegisterWindowMessageW(L"ccsuite.taskbar.relayout");
    if (relayoutMsg && m == relayoutMsg) {
        Slot *b = (Slot *)GetWindowLongPtrW(h, GWLP_USERDATA);
        if (b) relayout(b);
        return 1;   // answered, so the host can tell a live band from a dead one
    }
    return DefWindowProcW(h, m, w, l);
}

static int ensureClass(const wchar_t *name) {
    WNDCLASSW wc = {0};
    if (GetClassInfoW(g_inst, name, &wc)) return 1;
    wc.lpfnWndProc = slotProc;
    wc.hInstance = g_inst;
    wc.lpszClassName = name;
    wc.hbrBackground = CreateSolidBrush(RGB(42, 42, 42));
    return RegisterClassW(&wc) || GetLastError() == ERROR_CLASS_ALREADY_EXISTS;
}

static void killWindow(Slot *b) {
    if (!b->hwnd) return;
    SetWindowLongPtrW(b->hwnd, GWLP_USERDATA, 0);
    DestroyWindow(b->hwnd);
    b->hwnd = NULL;
}

static HRESULT slotQI(Slot *b, REFIID r, void **p) {
    if (!p) return E_POINTER;
    *p = NULL;
    if (IsEqualIID(r, &IID_IUnknown) || IsEqualIID(r, &IID_IOleWindow) || IsEqualIID(r, &IID_IDockingWindow) || IsEqualIID(r, &IID_IDeskBand)) *p = &b->band;
    else if (IsEqualIID(r, &IID_IObjectWithSite)) *p = &b->site;
    else if (IsEqualIID(r, &IID_IPersist) || IsEqualIID(r, &IID_IPersistStream)) *p = &b->persist;
    else return E_NOINTERFACE;
    InterlockedIncrement(&b->ref);
    return S_OK;
}

static void dropSite(Slot *b) {
    killWindow(b);
    if (b->pSite) { b->pSite->lpVtbl->Release(b->pSite); b->pSite = NULL; }
    b->parent = NULL;
}

static ULONG slotRelease(Slot *b) {
    LONG r = InterlockedDecrement(&b->ref);
    if (r == 0) { dropSite(b); free(b); InterlockedDecrement(&g_objects); }
    return (ULONG)r;
}

#define UNK(prefix, T, field) \
    static HRESULT STDMETHODCALLTYPE prefix##QI(T *t, REFIID r, void **p) { return slotQI((Slot *)((char *)t - offsetof(Slot, field)), r, p); } \
    static ULONG STDMETHODCALLTYPE prefix##AddRef(T *t) { return (ULONG)InterlockedIncrement(&((Slot *)((char *)t - offsetof(Slot, field)))->ref); } \
    static ULONG STDMETHODCALLTYPE prefix##Release(T *t) { return slotRelease((Slot *)((char *)t - offsetof(Slot, field))); }
UNK(bnd, IDeskBand, band)
UNK(sit, IObjectWithSite, site)
UNK(per, IPersistStream, persist)
#define OFBAND(t) ((Slot *)((char *)(t) - offsetof(Slot, band)))
#define OFSITE(t) ((Slot *)((char *)(t) - offsetof(Slot, site)))

static HRESULT STDMETHODCALLTYPE bndGetWindow(IDeskBand *t, HWND *h) {
    if (!h) return E_POINTER;
    *h = OFBAND(t)->hwnd;
    return *h ? S_OK : E_FAIL;
}
static HRESULT STDMETHODCALLTYPE bndHelp(IDeskBand *t, BOOL f) { return E_NOTIMPL; }
static HRESULT STDMETHODCALLTYPE bndShow(IDeskBand *t, BOOL f) {
    Slot *b = OFBAND(t);
    if (b->hwnd) ShowWindow(b->hwnd, f ? SW_SHOW : SW_HIDE);
    return S_OK;
}
static HRESULT STDMETHODCALLTYPE bndClose(IDeskBand *t, DWORD r) {
    Slot *b = OFBAND(t);
    killWindow(b);
    return S_OK;
}
static HRESULT STDMETHODCALLTYPE bndResize(IDeskBand *t, LPCRECT r, IUnknown *u, BOOL f) { return E_NOTIMPL; }

// How many cards long the band is: ccacc's 1 (the usage card alone) or 2 (with the free accounts' card), plus one for every card another tool registered under CARDS_KEY.
// The DLL reads both when the band is created (so toggling the band in the Toolbars menu, or restarting Explorer, picks up a change). Anything else than 1 or 2 for ccacc counts as 1.
static int cards(int *legacy) {
    DWORD v = 1, n = sizeof v, subs = 0;
    HKEY k;
    if (RegGetValueW(HKEY_CURRENT_USER, LEGACY_KEY, L"slotCards", RRF_RT_REG_DWORD, NULL, &v, &n) != ERROR_SUCCESS) v = 1;
    *legacy = v == 2 ? 2 : 1;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, CARDS_KEY, 0, KEY_READ, &k) == ERROR_SUCCESS) {
        if (RegQueryInfoKeyW(k, NULL, NULL, NULL, &subs, NULL, NULL, NULL, NULL, NULL, NULL, NULL) != ERROR_SUCCESS) subs = 0;
        RegCloseKey(k);
    }
    if (subs > MAX_CARDS) subs = MAX_CARDS;
    return *legacy + (int)subs > MAX_CARDS ? MAX_CARDS : *legacy + (int)subs;
}

static void className(int cards, wchar_t *out, size_t size) {
    if (cards <= 1) swprintf(out, size, L"%ls", CLASS_BASE);
    else swprintf(out, size, L"%ls%d", CLASS_BASE, cards);
}

// The band window's text is "legacy:total" (how many of its cards are ccacc's, how many it has): its class only says how long the band was when it was created.
static void bandText(Slot *b, wchar_t *out, size_t size) { swprintf(out, size, L"%d:%d", b->legacy, b->cards); }

// The registry changed under a live band: read it again, tell the Java side through the text, and ask Explorer for the band's size again.
static void relayout(Slot *b) {
    int legacy, n = cards(&legacy);
    if (!b->hwnd || (n == b->cards && legacy == b->legacy)) return;
    b->cards = n;
    b->legacy = legacy;
    wchar_t text[16];
    bandText(b, text, 16);
    SetWindowTextW(b->hwnd, text);
    IOleCommandTarget *ct = NULL;
    if (b->pSite && SUCCEEDED(b->pSite->lpVtbl->QueryInterface(b->pSite, &IID_IOleCommandTarget, (void **)&ct)) && ct) {
        ct->lpVtbl->Exec(ct, &CGID_DeskBand, DBID_BANDINFOCHANGED, OLECMDEXECOPT_DODEFAULT, NULL, NULL);
        ct->lpVtbl->Release(ct);
    }
}

// A vertical rebar reads the x value as the band's LENGTH (its height) and the y value as its thickness (its width); a horizontal one the reverse.
static HRESULT STDMETHODCALLTYPE bndInfo(IDeskBand *t, DWORD id, DWORD mode, DESKBANDINFO *i) {
    if (!i) return E_INVALIDARG;
    HWND p = OFBAND(t)->parent;
    RECT pr = {0};
    if (p) GetClientRect(p, &pr);
    double s = scale(p);
    int vertical = (mode & DBIF_VIEWMODE_VERTICAL) || pr.bottom > pr.right;
    int len = (int)((vertical ? CARD_H : CARD_W) * s) * OFBAND(t)->cards, thick = vertical && pr.right > 0 ? pr.right : !vertical && pr.bottom > 0 ? pr.bottom : (int)(CARD_H * s);
    if (i->dwMask & DBIM_MINSIZE) { i->ptMinSize.x = len; i->ptMinSize.y = thick; }
    if (i->dwMask & DBIM_MAXSIZE) { i->ptMaxSize.x = -1; i->ptMaxSize.y = -1; }
    if (i->dwMask & DBIM_INTEGRAL) { i->ptIntegral.x = 1; i->ptIntegral.y = 1; }
    if (i->dwMask & DBIM_ACTUAL) { i->ptActual.x = len; i->ptActual.y = thick; }
    if (i->dwMask & DBIM_MODEFLAGS) i->dwModeFlags = DBIMF_NORMAL;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE sitSetSite(IObjectWithSite *t, IUnknown *site) {
    Slot *b = OFSITE(t);
    dropSite(b);
    if (!site) return S_OK;
    IOleWindow *ow = NULL;
    HRESULT hr = site->lpVtbl->QueryInterface(site, &IID_IOleWindow, (void **)&ow);
    if (SUCCEEDED(hr)) { hr = ow->lpVtbl->GetWindow(ow, &b->parent); ow->lpVtbl->Release(ow); }
    b->cards = cards(&b->legacy);
    wchar_t cls[32], text[16];
    className(b->cards, cls, 32);
    bandText(b, text, 16);
    if (FAILED(hr) || !b->parent || !ensureClass(cls)) { b->parent = NULL; return E_FAIL; }
    b->pSite = site;
    site->lpVtbl->AddRef(site);
    b->hwnd = CreateWindowExW(0, cls, text, WS_CHILD | WS_CLIPSIBLINGS | WS_CLIPCHILDREN | WS_VISIBLE, 0, 0, 0, 0, b->parent, NULL, g_inst, NULL);
    if (b->hwnd) SetWindowLongPtrW(b->hwnd, GWLP_USERDATA, (LONG_PTR)b);
    return b->hwnd ? S_OK : E_FAIL;
}
static HRESULT STDMETHODCALLTYPE sitGetSite(IObjectWithSite *t, REFIID r, void **p) {
    Slot *b = OFSITE(t);
    if (!p) return E_POINTER;
    *p = NULL;
    return b->pSite ? b->pSite->lpVtbl->QueryInterface(b->pSite, r, p) : E_FAIL;
}

static HRESULT STDMETHODCALLTYPE perGetClassID(IPersistStream *t, CLSID *c) { if (!c) return E_POINTER; *c = CLSID_CcuSlot; return S_OK; }
static HRESULT STDMETHODCALLTYPE perIsDirty(IPersistStream *t) { return S_FALSE; }
static HRESULT STDMETHODCALLTYPE perLoad(IPersistStream *t, IStream *s) { return S_OK; }
static HRESULT STDMETHODCALLTYPE perSave(IPersistStream *t, IStream *s, BOOL c) { return S_OK; }
static HRESULT STDMETHODCALLTYPE perSize(IPersistStream *t, ULARGE_INTEGER *n) { if (n) n->QuadPart = 0; return S_OK; }

static IDeskBandVtbl bandVt = {bndQI, bndAddRef, bndRelease, bndGetWindow, bndHelp, bndShow, bndClose, bndResize, bndInfo};
static IObjectWithSiteVtbl siteVt = {sitQI, sitAddRef, sitRelease, sitSetSite, sitGetSite};
static IPersistStreamVtbl persistVt = {perQI, perAddRef, perRelease, perGetClassID, perIsDirty, perLoad, perSave, perSize};

static HRESULT STDMETHODCALLTYPE cfQI(IClassFactory *t, REFIID r, void **p) {
    if (!p) return E_POINTER;
    *p = NULL;
    if (!IsEqualIID(r, &IID_IUnknown) && !IsEqualIID(r, &IID_IClassFactory)) return E_NOINTERFACE;
    *p = t;
    return S_OK;
}
static ULONG STDMETHODCALLTYPE cfAddRef(IClassFactory *t) { return 2; }
static ULONG STDMETHODCALLTYPE cfRelease(IClassFactory *t) { return 1; }
static HRESULT STDMETHODCALLTYPE cfCreate(IClassFactory *t, IUnknown *outer, REFIID r, void **p) {
    if (!p) return E_POINTER;
    *p = NULL;
    if (outer) return CLASS_E_NOAGGREGATION;
    Slot *b = (Slot *)calloc(1, sizeof *b);
    if (!b) return E_OUTOFMEMORY;
    b->band.lpVtbl = &bandVt;
    b->site.lpVtbl = &siteVt;
    b->persist.lpVtbl = &persistVt;
    b->ref = 1;
    InterlockedIncrement(&g_objects);
    HRESULT hr = slotQI(b, r, p);
    slotRelease(b);
    return hr;
}
static HRESULT STDMETHODCALLTYPE cfLock(IClassFactory *t, BOOL l) { if (l) InterlockedIncrement(&g_locks); else InterlockedDecrement(&g_locks); return S_OK; }
static IClassFactoryVtbl cfVt = {cfQI, cfAddRef, cfRelease, cfCreate, cfLock};
static IClassFactory factory = {&cfVt};

static int regSet(const wchar_t *sub, const wchar_t *name, const wchar_t *val) {
    HKEY k;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, sub, 0, NULL, 0, KEY_WRITE, NULL, &k, NULL) != ERROR_SUCCESS) return 0;
    LONG r = RegSetValueExW(k, name, 0, REG_SZ, (const BYTE *)val, (DWORD)((wcslen(val) + 1) * sizeof(wchar_t)));
    RegCloseKey(k);
    return r == ERROR_SUCCESS;
}

STDAPI DllRegisterServer(void) {
    wchar_t path[MAX_PATH], clsid[64], key[200];
    DWORD n = GetModuleFileNameW(g_inst, path, MAX_PATH);
    if (!n || n >= MAX_PATH || !StringFromGUID2(&CLSID_CcuSlot, clsid, 64)) return E_FAIL;
    swprintf(key, 200, L"Software\\Classes\\CLSID\\%ls", clsid);
    int ok = regSet(key, NULL, BAND_NAME);
    swprintf(key, 200, L"Software\\Classes\\CLSID\\%ls\\InprocServer32", clsid);
    ok = ok && regSet(key, NULL, path) && regSet(key, L"ThreadingModel", L"Apartment");
    swprintf(key, 200, L"Software\\Classes\\CLSID\\%ls\\Implemented Categories\\%ls", clsid, CATID_DESKBAND);
    ok = ok && regSet(key, NULL, L"");
    return ok ? S_OK : E_FAIL;
}

STDAPI DllUnregisterServer(void) {
    wchar_t clsid[64], key[200];
    if (!StringFromGUID2(&CLSID_CcuSlot, clsid, 64)) return E_FAIL;
    swprintf(key, 200, L"Software\\Classes\\CLSID\\%ls", clsid);
    LONG r = RegDeleteTreeW(HKEY_CURRENT_USER, key);
    return r == ERROR_SUCCESS || r == ERROR_FILE_NOT_FOUND ? S_OK : E_FAIL;
}

STDAPI DllGetClassObject(REFCLSID c, REFIID r, void **p) {
    if (!p) return E_POINTER;
    *p = NULL;
    if (!IsEqualCLSID(c, &CLSID_CcuSlot)) return CLASS_E_CLASSNOTAVAILABLE;
    return cfQI(&factory, r, p);
}
STDAPI DllCanUnloadNow(void) { return g_objects == 0 && g_locks == 0 ? S_OK : S_FALSE; }

BOOL WINAPI DllMain(HINSTANCE h, DWORD why, LPVOID reserved) {
    if (why == DLL_PROCESS_ATTACH) { g_inst = h; DisableThreadLibraryCalls(h); }
    if (why == DLL_PROCESS_DETACH && !reserved) {   // the classes' window procedure lives in this DLL, so none may outlive it
        wchar_t cls[32];
        for (int n = 1; n <= MAX_CARDS; n++) { className(n, cls, 32); UnregisterClassW(cls, h); }
    }
    return TRUE;
}
