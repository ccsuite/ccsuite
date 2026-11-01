package ccsuite.cclocalusagestats.model;

import datapotter.arcadedbhelper.ArcadeData;
import datapotter.arcadedbhelper.TypeDef;

@ArcadeData
public final class ActiveMinute extends ActiveMinute_A {

    String key;
    String sessionId;
    Long minute;

    public static final TypeDef<ActiveMinute> TYPEDEF =
        schemaBuilder()
            .factory(ActiveMinute::new)
            .unique($key)
            .lsmIndex($sessionId)
            .__();
}
