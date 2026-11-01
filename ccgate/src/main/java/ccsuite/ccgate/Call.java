package ccsuite.ccgate;

import datapotter.datahelper.Data;

/** One incoming request as the gate sees it: who sent it, under which grant, for which model, with which tags. */
@Data
public final class Call extends Call_A {
    String key;
    String keyFingerprint;
    Grant grant;
    KeyRef keyRef;
    String session;
    String agent;
    String project;
    String job;
    String remoteUser;
    String modelAsked;
    String family;
    boolean stream;
}
