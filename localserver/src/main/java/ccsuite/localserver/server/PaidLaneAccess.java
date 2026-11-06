package ccsuite.localserver.server;

import java.util.List;

/** The pure part of the paid lane's account choice, public so it can be tested from the lanes package. */
public final class PaidLaneAccess {

    /** See {@link PaidLane#candidates}. */
    public static List<String> candidates(List<String> ranked, String pinned) { return PaidLane.candidates(ranked, pinned); }

    private PaidLaneAccess() {}
}
