package com.github.tink_api_with_charts.cinfiguration;

import lombok.Data;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

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

  /**
   * Список пар инструментов для мониторинга (устаревший формат для одной пары)
   */
  @Deprecated
  private String shareUid;

  @Deprecated
  private String futureUid;

  /**
   * Список пар инструментов для мониторинга
   */
  private List<InstrumentPair> pairs;

  public long getBalance() {
    return balance;
  }

  public void setBalance(long balance) {
    this.balance = balance;
  }

  @Deprecated
  public String getShareUid() {
    return shareUid;
  }

  @Deprecated
  public void setShareUid(String shareUid) {
    this.shareUid = shareUid;
  }

  @Deprecated
  public String getFutureUid() {
    return futureUid;
  }

  @Deprecated
  public void setFutureUid(String futureUid) {
    this.futureUid = futureUid;
  }

  public List<InstrumentPair> getPairs() {
    return pairs;
  }

  public void setPairs(List<InstrumentPair> pairs) {
    this.pairs = pairs;
  }

  /**
   * Класс представляющий пару инструментов акция-фьючерс
   */
  @Data
  public static class InstrumentPair {
    /**
     * Имя пары в формате "shareTicker-futureTicker"
     */
    private String name;

    /**
     * UID акции
     */
    private String shareUid;

    /**
     * UID фьючерса
     */
    private String futureUid;

    /**
     * Количество акций в одном фьючерсе (размер лота фьючерса)
     */
    private int futureLot = 1;
  }
}
