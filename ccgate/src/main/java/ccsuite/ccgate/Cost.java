package ccsuite.ccgate;

import datapotter.datahelper.Data;

/** What one call cost: quota points (1 point = 1% of one account's 5-hour window) and the list-price equivalent in USD. */
@Data
public final class Cost extends Cost_A {
    String family;
    double points;
    double dollars;
}
