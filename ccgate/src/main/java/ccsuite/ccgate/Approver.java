package ccsuite.ccgate;

import module java.base;

/**
 * Whoever may say yes to a caller the gate does not know: in ccacc a window the owner answers, headless nobody. Called on
 * a request's own thread, so it may block up to {@code wait}.
 */
public interface Approver {

    /** The owner's decision; empty when declined. Throws TimeoutException when nobody answered within {@code wait}. */
    Optional<Approval> ask(ApprovalRequest request, Duration wait) throws TimeoutException;
}
