package ccsuite.ccgate;

import module java.base;

/** Where the gate's calls go out: which accounts to try in order, and each one's token and account id. */
public interface Accounts extends AutoCloseable {

    /** Accounts to try, best first; empty when none may serve now. */
    List<String> candidates();

    Optional<String> token(String id);

    /** The account's own id, written into the request's metadata.user_id; null when unknown. */
    String uuid(String id);

    void failed401(String id);

    void served(String id);

    @Override
    default void close() { }
}
