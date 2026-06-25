package com.github.tink_api_with_charts;

import org.slf4j.Logger;
import org.ta4j.core.AnalysisCriterion;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.analysis.cost.LinearTransactionCostModel;
import org.ta4j.core.backtest.TradeOnCurrentCloseModel;
import org.ta4j.core.criteria.pnl.NetProfitCriterion;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import ru.tinkoff.piapi.contract.v1.CandleInterval;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;
import ru.ttech.piapi.strategy.BacktestStrategyFactory;
import ru.ttech.piapi.strategy.candle.backtest.CandleStrategyBacktestConfiguration;


import java.time.LocalDate;
import java.util.concurrent.Executors;
import java.util.function.Function;

public class BackTestExample {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(BackTestExample.class);

    public static void main(String[] args) {
        var configuration = ConnectorConfiguration.loadPropertiesFromFile("config/application.yml");
        var backtestStrategyFactory = BacktestStrategyFactory.create(configuration);
        var executorService = Executors.newCachedThreadPool();

        Function<BarSeries, Strategy> tradingStrategy = barSeries -> {
            ClosePriceIndicator closePrice = new ClosePriceIndicator(barSeries);
            EMAIndicator shortEma = new EMAIndicator(closePrice, 5);
            EMAIndicator longEma = new EMAIndicator(closePrice, 6);
            Rule buyingRule = new CrossedUpIndicatorRule(shortEma, longEma);
            Rule sellingRule = new CrossedDownIndicatorRule(shortEma, longEma);
            return new BaseStrategy(buyingRule, sellingRule);
        };

        var backtest = backtestStrategyFactory.newCandleStrategyBacktest(
                CandleStrategyBacktestConfiguration.builder()
                        .setInstrumentId("87db07bc-0e02-4e29-90bb-05e8ef791d7b")
                        .setCandleInterval(CandleInterval.CANDLE_INTERVAL_5_MIN)
                        .setFrom(LocalDate.of(2025, 1, 1))
                        .setTo(LocalDate.of(2026, 6, 23))
                        .setTradeExecutionModel(new TradeOnCurrentCloseModel())
                        .setTradeFeeModel(new LinearTransactionCostModel(0))
                        .setExecutorService(executorService)
                        .setStrategyAnalysis(barSeriesManager -> {
                            AnalysisCriterion criterion = new NetProfitCriterion();
                            var barSeries = barSeriesManager.getBarSeries();
                            var tradingRecord = barSeriesManager.run(tradingStrategy.apply(barSeries));
                            var profit = criterion.calculate(barSeries, tradingRecord);
                            tradingRecord.getTrades().forEach(trade ->
                                    logger.info("Trade: side: {}, price: {}, time: {}",
                                            trade.getType(),
                                            trade.getPricePerAsset(),
                                            barSeries.getBar(trade.getIndex()).getBeginTime()
                                    ));
                            logger.info("Profit: {}, Total trades: {}", profit, tradingRecord.getTrades().size());
                        })
                        .build());
        backtest.run();
        executorService.shutdownNow();
    }
}
