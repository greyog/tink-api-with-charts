package com.github.tink_api_with_charts.cinfiguration;

import lombok.Data;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;

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

  public long getBalance() {
    return balance;
  }

  public void setBalance(long balance) {
    this.balance = balance;
  }

  public String getShareUid() {
    return shareUid;
  }

  public void setShareUid(String shareUid) {
    this.shareUid = shareUid;
  }

  public String getFutureUid() {
    return futureUid;
  }

  public void setFutureUid(String futureUid) {
    this.futureUid = futureUid;
  }
}