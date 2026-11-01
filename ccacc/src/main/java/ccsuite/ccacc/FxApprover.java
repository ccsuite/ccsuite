package ccsuite.ccacc;

import module java.base;
import ccsuite.ccgate.Approval;
import ccsuite.ccgate.ApprovalRequest;
import ccsuite.ccgate.Approver;
import javafx.application.Platform;
import leasegate.frame.Brand;
import leasegate.frame.GateIcons;

/** Asks the owner at a window on the JavaFX thread, one window at a time; the request's own thread waits for the answer. */
final class FxApprover implements Approver {

    final Brand brand = new Brand("ccacc gate", CcaccApp.COLOR, new GateIcons(CcaccApp.class, "ccacc"));
    final Semaphore oneAtATime = new Semaphore(1);

    @Override public Optional<Approval> ask(ApprovalRequest request, Duration wait) throws TimeoutException {
        var deadline = System.nanoTime() + wait.toNanos();
        try {
            if (!oneAtATime.tryAcquire(wait.toNanos(), TimeUnit.NANOSECONDS)) throw new TimeoutException();
            try {
                var answer = new CompletableFuture<Optional<Approval>>();
                var window = new AtomicReference<ApprovalWindow>();
                Platform.runLater(() -> {
                    var w = new ApprovalWindow(request, brand, answer::complete);
                    window.set(w);
                    w.show();
                });
                try {
                    return answer.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    Platform.runLater(() -> { var w = window.get(); if (w != null) w.withdraw(); });
                    throw e;
                }
            } finally {
                oneAtATime.release();
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new TimeoutException(String.valueOf(e));
        }
    }
}
