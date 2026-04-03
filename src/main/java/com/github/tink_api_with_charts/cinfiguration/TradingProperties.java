package com.github.tink_api_with_charts.cinfiguration;

import lombok.Data;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "trading.bot")
public class TradingProperties {
  /**
   * Количество лотов одного инструмента, которыми будет торговать бот
   */
  private long lots;
  /**
  * Баланс счёта песочницы
   */
  private long balance;

  private String shareUid;

  private String futureUid;

}