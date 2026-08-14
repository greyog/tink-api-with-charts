package com.github.tink_api_with_charts.cinfiguration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading.balancer")
@Data
public class BalancerProperties {

  private boolean canTrade;

  private String accountId;

  private double targetShareAllocation;

  private double rebalanceThresholdUp;

  private double rebalanceThresholdDown;

  private String shareUid;

  private String cashEtfUid;

  private long iisCashEtfQty = 2501;

  /** Интервал периодической сверки заявок с API (мс) */
  private long orderReconciliationIntervalMs = 30000;

  /** Задержка перед первой проверкой статуса после отправки заявки (мс) */
  private long orderRecoveryDelayMs = 5000;

  /** Максимальное количество попыток восстановления статуса заявки */
  private int maxRecoveryRetries = 5;

}
