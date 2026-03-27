package com.github.tink_api_with_charts.component;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import ru.tinkoff.piapi.contract.v1.CandleInstrument;
import ru.tinkoff.piapi.contract.v1.GetCandlesRequest;
import ru.tinkoff.piapi.contract.v1.SubscriptionInterval;
import ru.ttech.piapi.springboot.bot.CandleTradingBot;

import java.util.Map;
import java.util.function.Function;

//@Component
@Slf4j
public class MyCandleTradingBot implements CandleTradingBot {

 @Override
 public GetCandlesRequest.CandleSource getCandleSource() {
     // источник свечных данных
     return GetCandlesRequest.CandleSource.CANDLE_SOURCE_INCLUDE_WEEKEND;
 }

 @Override
 public int getWarmupLength() {
     // количество свечей, которые будут загружены для стабилизации значений индикаторов
     // лучше ставить это значение равным самому большому периоду индикатора, используемого в Вашей стратегии
     return 100;
 }

 @Override
 public Map<CandleInstrument, Function<BarSeries, Strategy>> getStrategies() {
     log.info("настраиваем бота на торговлю бумагой Т-Технологии по стратегии ta4j");
     // настраиваем бота на торговлю бумагой Т-Технологии по стратегии ta4j
     var ttechShare = CandleInstrument.newBuilder()
             .setInstrumentId("87db07bc-0e02-4e29-90bb-05e8ef791d7b")
             .setInterval(SubscriptionInterval.SUBSCRIPTION_INTERVAL_ONE_MINUTE)
             .build();
     return Map.of(
             ttechShare, createStrategy(5, 10)
     );
 }

 @Override
 public void onStrategyEnterAction(CandleInstrument instrument, Bar bar) {
     // выполняем действие при входе в позицию
     log.info("Entering position for instrument {} by price: {}", instrument.getInstrumentId(), bar.getClosePrice());
 }

 @Override
 public void onStrategyExitAction(CandleInstrument instrument, Bar bar) {
     // выполняем действие при выходе из позиции
     log.info("Exiting position for instrument {} by price: {}", instrument.getInstrumentId(), bar.getClosePrice());
 }

 // Задаём стратегию на двух индикаторах EMA
 public Function<BarSeries, Strategy> createStrategy(int shortEmaPeriod, int longEmaPeriod) {
     return barSeries -> {
         ClosePriceIndicator closePrice = new ClosePriceIndicator(barSeries);
         EMAIndicator shortEma = new EMAIndicator(closePrice, shortEmaPeriod);
         EMAIndicator longEma = new EMAIndicator(closePrice, longEmaPeriod);
         Rule buyingRule = new CrossedUpIndicatorRule(shortEma, longEma);
         Rule sellingRule = new CrossedDownIndicatorRule(shortEma, longEma);
         return new BaseStrategy(buyingRule, sellingRule);
     };
 }
}