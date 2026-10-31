package ccsuite.base.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * POJO for extra usage data (paid credits/usage beyond base quota)
 * Used in both personal accounts (with Pro/Team plans) and team accounts
 * Ignores unknown properties to handle API changes gracefully
 */
@Data
@Accessors(fluent = true, chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtraUsage {

    @JsonProperty("is_enabled")
    Boolean isEnabled;

    @JsonProperty("monthly_limit")
    Double monthlyLimit; // Can be null

    @JsonProperty("used_credits")
    Double usedCredits; // Can be null

    @JsonProperty("utilization")
    Double utilization; // Can be null - percentage of monthly limit used
}
