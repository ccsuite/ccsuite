package ccsuite.quotaweights;

/**
 * What a set of tokens weighs: quota points (1 point = 1% of one account's 5-hour window) and the list-price equivalent in USD.
 * assumedPoints is the part of points that rests on an assumed weight (see the table's note), never more than points.
 */
public record Cost(String family, double points, double assumedPoints, double dollars) {

    public boolean fullyMeasured() {
        return assumedPoints == 0;
    }
}
