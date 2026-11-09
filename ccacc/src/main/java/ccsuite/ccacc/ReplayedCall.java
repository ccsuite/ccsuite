package ccsuite.ccacc;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.experimental.Accessors;

/** PRP 87: one call of the request log, rebuilt by replaying the session's lines: the whole request body, and the metadata line it came from. */
@Data
@Accessors(fluent = true, chain = true)
final class ReplayedCall {
    JsonNode request, meta;
}
