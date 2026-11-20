package ccsuite.taskbarslot;

/** What {@link Slot#tick} found: no band to go into yet, the card in the band, or the band gone (Explorer restarted), so the window is dead and must be made again. */
public enum SlotState { WAITING, EMBEDDED, LOST }
