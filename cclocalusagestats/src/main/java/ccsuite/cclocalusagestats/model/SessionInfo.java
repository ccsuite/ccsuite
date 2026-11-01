package ccsuite.cclocalusagestats.model;

import datapotter.arcadedbhelper.ArcadeData;
import datapotter.arcadedbhelper.TypeDef;

@ArcadeData
public final class SessionInfo extends SessionInfo_A {

    String sessionId;
    String cwd;
    String prp;
    String entrypoint;
    Long firstTs;
    Long lastTs;

    public static final TypeDef<SessionInfo> TYPEDEF =
        schemaBuilder()
            .factory(SessionInfo::new)
            .unique($sessionId)
            .__();
}
