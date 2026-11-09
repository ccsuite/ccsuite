package ccsuite.ccacc;

import javafx.scene.input.KeyCode;

/**
 * One action of a page and its key: the key handler of the window and the button of its legend are both made from this one declaration, so the legend can never
 * advertise a key nobody bound, and a legend item and its key do the same thing. Function keys only (they are not typing); a window with a text box owes every letter to it.
 */
record KeyAction(KeyCode key, String label, Runnable run) {

    /** What the legend shows: the key worn on the control, "F5 Read now". */
    String text() { return (key == KeyCode.ESCAPE ? "Esc" : key.getName()) + " " + label; }
}
