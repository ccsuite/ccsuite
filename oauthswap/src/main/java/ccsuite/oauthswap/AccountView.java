package ccsuite.oauthswap;

/** A row for {@code list}/{@code status}: an eligible account joined with live + stored state. */
public record AccountView(
        String id,
        String email,
        String name,
        String orgUuid,
        String limit,
        boolean registered,
        boolean active) {}
