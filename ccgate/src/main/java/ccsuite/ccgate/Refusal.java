package ccsuite.ccgate;

import datapotter.datahelper.Data;

/** Why the gate will not forward a call, as an HTTP status and one plain sentence for the caller. */
@Data
public final class Refusal extends Refusal_A {
    int status;
    String reason;

    public static Refusal of(int status, String reason) {
        return new Refusal().status(status).reason(reason);
    }
}
