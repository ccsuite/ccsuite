package ccsuite.base.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * POJO for CCAPI usage data
 * Supports both personal accounts and team organization accounts
 * Ignores unknown properties to handle API changes gracefully
 */
@Data
@Accessors(fluent = true, chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class UsageData {

    @JsonProperty("five_hour")
    Usage fiveHour;

    @JsonProperty("seven_day")
    Usage sevenDay;

    @JsonProperty("seven_day_oauth_apps")
    Usage sevenDayOauthApps;

    @JsonProperty("seven_day_opus")
    Usage sevenDayOpus;

    @JsonProperty("seven_day_sonnet")
    Usage sevenDaySonnet; // Team account field

    @JsonProperty("seven_day_cowork")
    Usage sevenDayCowork; // Team account field

    @JsonProperty("iguana_necktie")
    Usage iguanaNecktie; // Future/experimental field

    @JsonProperty("extra_usage")
    ExtraUsage extraUsage; // Paid credits beyond base quota (personal Pro/Team and team accounts)

}