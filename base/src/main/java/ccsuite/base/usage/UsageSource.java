package ccsuite.base.usage;

import ccsuite.base.model.UsageData;

import java.io.IOException;

/** Where one account's usage windows come from: the claude.ai cookie API, or Claude Code's OAuth side. */
public interface UsageSource extends AutoCloseable {

    /** One attempt for the account saved under this id; each source finds its own secret. Throws when the source cannot answer. */
    UsageData fetchUsage(String accountId) throws IOException;

    /** Releases connections and threads; no checked exception. */
    @Override
    void close();
}
