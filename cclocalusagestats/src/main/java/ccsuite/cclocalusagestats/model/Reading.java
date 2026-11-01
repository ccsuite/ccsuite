package ccsuite.cclocalusagestats.model;

import datapotter.arcadedbhelper.ArcadeData;
import datapotter.arcadedbhelper.TypeDef;

/** One utilization reading of one account, as the account watcher wrote it: percent of the 5-hour and 7-day windows. */
@ArcadeData
public final class Reading extends Reading_A {

    String key;
    String account;
    Long ts;
    Double u5h;
    Double u7d;
    Long reset5h;
    Long reset7d;
    Boolean active;

    public static final TypeDef<Reading> TYPEDEF =
        schemaBuilder()
            .factory(Reading::new)
            .unique($key)
            .lsmIndex($ts)
            .__();
}
