package ccsuite.cclocalusagestats.model;

import datapotter.arcadedbhelper.ArcadeData;
import datapotter.arcadedbhelper.TypeDef;

@ArcadeData
public final class FileState extends FileState_A {

    String path;
    Long offset;
    Long lastTs;

    public static final TypeDef<FileState> TYPEDEF =
        schemaBuilder()
            .factory(FileState::new)
            .unique($path)
            .__();
}
