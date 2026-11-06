package ccsuite.localserver.lanes;

/** How a lane's answer ended: the HTTP status that went to the client, and which account served it when the lane knows (the paid lane does; the free lane picks inside its proxy). */
public record Result(int status, String account) {
    public static Result done(int status) { return new Result(status, null); }
    public static Result done(int status, String account) { return new Result(status, account); }
}
