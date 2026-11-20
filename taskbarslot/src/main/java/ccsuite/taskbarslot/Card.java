package ccsuite.taskbarslot;

import lombok.*;
import lombok.experimental.Accessors;

/** One registered card: its id (the registry subkey), the title of the window to host, and its order in the band. */
@Data @Accessors(fluent = true) @AllArgsConstructor
public class Card {
    String id, title;
    int order;
}
