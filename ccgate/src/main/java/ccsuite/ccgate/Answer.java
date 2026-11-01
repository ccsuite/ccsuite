package ccsuite.ccgate;

import datapotter.datahelper.Data;

/** What the gate learns from an answer as it passes through: its id, the model that answered, the usage, its size. */
@Data
public final class Answer extends Answer_A {
    String messageId;
    String model;
    String stopReason;
    Usage usage = new Usage();
    long bytes;
}
