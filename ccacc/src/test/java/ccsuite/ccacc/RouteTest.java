package ccsuite.ccacc;

import org.junit.jupiter.api.Test;

import static ccsuite.ccacc.Route.*;
import static org.junit.jupiter.api.Assertions.*;

class RouteTest {

    @Test void noArgumentsStartTheResidentApp() { assertEquals(APP, Route.of(new String[]{})); }

    @Test void aFlagToLeaveAPartOutAlsoStartsTheApp() {
        assertEquals(APP, Route.of(new String[]{"--no-server"}));
        assertArrayEquals(new String[]{"--no-server"}, Route.rest(new String[]{"--no-server"}));
    }

    @Test void theCommandWordIsDroppedFromWhatTheRouteReceives() {
        assertEquals(SERVE, Route.of(new String[]{"serve", "--port", "9"}));
        assertArrayEquals(new String[]{"--port", "9"}, Route.rest(new String[]{"serve", "--port", "9"}));
        assertEquals(TUI, Route.of(new String[]{"tui"}));
        assertArrayEquals(new String[]{"--no-watch"}, Route.rest(new String[]{"app", "--no-watch"}));
    }

    @Test void theOldToolNamesTakeTheirOldOptions() {
        assertEquals(CCU, Route.of(new String[]{"ccu", "--cli"}));
        assertArrayEquals(new String[]{"--cli"}, Route.rest(new String[]{"ccu", "--cli"}));
        assertEquals(CCLS, Route.of(new String[]{"ccls", "--port", "9"}));
        assertEquals(SWAP, Route.of(new String[]{"cswap", "list"}));
        assertArrayEquals(new String[]{"list"}, Route.rest(new String[]{"cswap", "list"}));
    }

    @Test void theCommandsThatChangeTheRunningAppHaveTheirOwnRoute() {
        assertEquals(CONTROL, Route.of(new String[]{"mode", "locked"}));
        assertEquals(CONTROL, Route.of(new String[]{"account", "list"}));
        assertArrayEquals(new String[]{"part", "server", "off"}, Route.rest(new String[]{"part", "server", "off"}));
    }

    @Test void migrateIsOneOfTheExesOwnCommands() {
        assertEquals(MIGRATE, Route.of(new String[]{"migrate", "--apply"}));
        assertArrayEquals(new String[]{"--apply"}, Route.rest(new String[]{"migrate", "--apply"}));
    }

    @Test void theExesOwnLifeHasItsOwnRoutes() {
        assertEquals(INSTALL, Route.of(new String[]{"install", "--start-at-login"}));
        assertArrayEquals(new String[]{"--start-at-login"}, Route.rest(new String[]{"install", "--start-at-login"}));
        assertEquals(UNINSTALL, Route.of(new String[]{"uninstall"}));
        assertEquals(UPDATE, Route.of(new String[]{"update"}));
    }

    @Test void everythingElseIsACswapCommandAndKeepsItsWord() {
        assertEquals(SWAP, Route.of(new String[]{"list"}));
        assertEquals(SWAP, Route.of(new String[]{"watch", "--once"}));
        assertArrayEquals(new String[]{"watch", "--once"}, Route.rest(new String[]{"watch", "--once"}));
    }
}
