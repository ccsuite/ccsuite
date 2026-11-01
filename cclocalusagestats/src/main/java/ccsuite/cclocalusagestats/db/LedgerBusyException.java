package ccsuite.cclocalusagestats.db;

import java.nio.file.Path;

public final class LedgerBusyException extends RuntimeException {

    public LedgerBusyException(Path dir, Throwable cause) {
        super("The ledger (" + dir + ") is open in another cclocalusagestats, most likely the window. Close it, or use the window instead.", cause);
    }
}
