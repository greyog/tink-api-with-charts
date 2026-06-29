package com.github.tink_api_with_charts.cinfiguration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading.balancer")
@Data
public class BalancerProperties {

  private String accountId;

  private double targetShareAllocation;

  private double rebalanceThreshold;

  private String shareUid;

  private String cashEtfUid;

}
