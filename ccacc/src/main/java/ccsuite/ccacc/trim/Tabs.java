package ccsuite.ccacc.trim;

import module java.base;

/**
 * Optional input from the terminal Tabs (fxterm-tabs): when it answers {@code fxterm-tabs ctl list}, the tabs the user is looking at (the selected tab of each window) and the tabs whose Claude is busy are held back,
 * and each tab's Claude session name goes into the log line. Tabs not installed, not running or not answering in time gives an empty answer: the other idle rules stand on their own. Asked only when a trim is about to happen.
 */
final class Tabs {

    static TabsInfo parse(String json) {
        var out = new TabsInfo();
        try {
            var root = TrimJson.JSON.readTree(json);
            if (!root.path("ok").asBoolean(false)) return out;
            for (var window : root.path("windows"))
                for (var tab : window.path("tabs")) {
                    if (!tab.path("live").asBoolean(false) || !tab.has("pid")) continue;
                    var pid = tab.path("pid").asLong();
                    var claude = tab.path("claude");
                    if (tab.path("selected").asBoolean(false) || "busy".equals(claude.path("status").asText(""))) out.hold().add(pid);
                    var name = claude.path("name").asText(tab.path("title").asText(""));
                    if (!name.isEmpty()) out.names().put(pid, name);
                }
        } catch (IOException | RuntimeException e) {
            return new TabsInfo();
        }
        return out;
    }

    /** Asks Tabs; an empty answer when it does not respond within a few seconds. */
    static TabsInfo fetch() {
        try {
            var p = new ProcessBuilder("cmd", "/c", "fxterm-tabs", "ctl", "list").redirectErrorStream(true).start();
            p.getOutputStream().close();
            var reader = CompletableFuture.supplyAsync(() -> { try { return new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); } catch (IOException e) { return ""; } });
            if (!p.waitFor(8, TimeUnit.SECONDS)) { p.destroyForcibly(); return new TabsInfo(); }
            return parse(reader.get(2, TimeUnit.SECONDS));
        } catch (Exception e) {
            return new TabsInfo();
        }
    }

    private Tabs() {}
}
