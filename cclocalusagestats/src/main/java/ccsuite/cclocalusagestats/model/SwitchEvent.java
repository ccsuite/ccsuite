package ccsuite.cclocalusagestats.model;

import datapotter.arcadedbhelper.ArcadeData;
import datapotter.arcadedbhelper.TypeDef;

@ArcadeData
public final class SwitchEvent extends SwitchEvent_A {

    String key;
    String fromAccount;
    String toAccount;
    Long ts;

    public static final TypeDef<SwitchEvent> TYPEDEF =
        schemaBuilder()
            .factory(SwitchEvent::new)
            .unique($key)
            .lsmIndex($ts)
            .__();
}
