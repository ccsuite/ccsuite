package ccsuite.ccacc;

import module java.base;

/** A tab of the main window: built when first in front of a shown window, kept current only while it is, stopped when it is not. */
interface Page {

    void start();

    void stop();

    /** The function-key actions of this tab while it is in front; the window shows them as its legend and binds them. */
    default List<KeyAction> keys() { return List.of(); }
}
