package com.github.tink_api_with_charts;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseTradingRecord;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.backtest.TradeOnCurrentCloseModel;
import org.ta4j.core.num.NumFactory;
import ru.tinkoff.piapi.contract.v1.CandleInterval;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;
import ru.ttech.piapi.strategy.BacktestStrategyFactory;
import ru.ttech.piapi.strategy.candle.backtest.CandleStrategyBacktestConfiguration;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RebalanceBacktestExample {
    private static final Logger log = LoggerFactory.getLogger(RebalanceBacktestExample.class);

    // ⚙️ ВХОДЯЩИЕ ПАРАМЕТРЫ АЛГОРИТМА
    private static final double INITIAL_CAPITAL = 1_000_000.0; // Стартовая сумма
    private static final double TARGET_ALLOCATION = 0.50;      // Целевая доля акций (50%)
    private static final double REBALANCE_THRESHOLD = 0.0005;    // Порог срабатывания (±1%)

    public static void main(String[] args) {
        var configuration = ConnectorConfiguration.loadPropertiesFromFile("config/application.yml");
        var backtestStrategyFactory = BacktestStrategyFactory.create(configuration);
        var executorService = Executors.newSingleThreadExecutor();

        try {
            var backtest = backtestStrategyFactory.newCandleStrategyBacktest(
                    CandleStrategyBacktestConfiguration.builder()
                            .setInstrumentId("87db07bc-0e02-4e29-90bb-05e8ef791d7b")
                            .setCandleInterval(CandleInterval.CANDLE_INTERVAL_1_MIN)
                            .setFrom(LocalDate.of(2024, 1, 1))
                            .setTo(LocalDate.of(2026, 6, 23))
                            .setTradeExecutionModel(new TradeOnCurrentCloseModel())
                            .setTradeFeeModel(new org.ta4j.core.analysis.cost.LinearTransactionCostModel(0.0)) // 0.1% комиссия
                            .setExecutorService(executorService)
                            .setStrategyAnalysis(barSeriesManager -> {
                                BarSeries series = barSeriesManager.getBarSeries();
                                NumFactory numFactory = series.numFactory(); // 🔑 Ключевое: предотвращает ClassCastException в новых версиях TA4J

                                double cash = INITIAL_CAPITAL;
                                int shares = 0;
                                TradingRecord tradingRecord = new BaseTradingRecord();

                                // 📊 Коллекторы для визуализации (одинаковая длина)
                                List<Long> timestamps = new ArrayList<>();
                                List<Double> prices = new ArrayList<>();
                                List<Double> equity = new ArrayList<>();
                                List<Integer> tradeIndices = new ArrayList<>();
                                List<String> tradeTypes = new ArrayList<>();

                                for (int i = 0; i < series.getBarCount(); i++) {
                                    double price = series.getBar(i).getClosePrice().doubleValue();
                                    if (price > 1000) {
                                        price = price / 10;
                                    }
                                    timestamps.add(series.getBar(i).getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
                                    prices.add(price);

                                    if (i == 0) {
                                        equity.add(INITIAL_CAPITAL);
                                        int initialBuyShares = (int) Math.floor((INITIAL_CAPITAL * TARGET_ALLOCATION) / price);
                                        tradingRecord.enter(i, numFactory.numOf(price), numFactory.numOf(initialBuyShares));
                                        cash -= initialBuyShares * price;
                                        shares = initialBuyShares;
                                        tradeIndices.add(i);
                                        tradeTypes.add("BUY");
                                        continue;
                                    }

                                    double stockValue = shares * price;
                                    double totalValue = cash + stockValue;
                                    equity.add(totalValue);

                                    if (totalValue <= 0) continue;
                                    double currentAllocation = stockValue / totalValue;

                                    if (currentAllocation >= TARGET_ALLOCATION + REBALANCE_THRESHOLD) {
                                        double targetStockValue = totalValue * TARGET_ALLOCATION;
                                        int sharesToSell = (int) Math.floor((stockValue - targetStockValue) / price);
                                        if (sharesToSell > 0) {
                                            tradingRecord.operate(i, numFactory.numOf(price), numFactory.numOf(-sharesToSell));
                                            cash += sharesToSell * price;
                                            shares -= sharesToSell;
                                            tradeIndices.add(i);
                                            tradeTypes.add("SELL");
                                        }
                                    } else if (currentAllocation <= TARGET_ALLOCATION - REBALANCE_THRESHOLD) {
                                        double targetStockValue = totalValue * TARGET_ALLOCATION;
                                        int sharesToBuy = (int) Math.floor((targetStockValue - stockValue) / price);
                                        if (sharesToBuy > 0) {
                                            tradingRecord.operate(i, numFactory.numOf(price), numFactory.numOf(sharesToBuy));
                                            cash -= sharesToBuy * price;
                                            shares += sharesToBuy;
                                            tradeIndices.add(i);
                                            tradeTypes.add("BUY");
                                        }
                                    }
                                }

                                // 🖼️ Запуск визуализации в отдельном потоке
                                renderBacktestChart(timestamps, prices, equity, tradeIndices, tradeTypes, cash, shares);
                            })
                            .build());

            backtest.run();
        } catch (Exception e) {
            log.error("❌ Backtest failed", e);
        } finally {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    log.warn("⏳ Executor did not terminate gracefully.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("💤 Shutdown interrupted", e);
            }
        }
    }

    private static void renderBacktestChart(List<Long> timestamps, List<Double> prices, List<Double> equity,
                                            List<Integer> tradeIndices, List<String> tradeTypes,
                                            double finalCash, int finalShares) {
        // 1️⃣ Формируем серии
        XYSeries priceSeries = new XYSeries("Цена актива");
        XYSeries equitySeries = new XYSeries("Стоимость портфеля");
        XYSeries buySeries = new XYSeries("Покупки");
        XYSeries sellSeries = new XYSeries("Продажи");

        for (int i = 0; i < timestamps.size(); i++) {
            priceSeries.add(timestamps.get(i), prices.get(i));
            equitySeries.add(timestamps.get(i), equity.get(i));
        }

        for (int i = 0; i < tradeIndices.size(); i++) {
            int idx = tradeIndices.get(i);
            String type = tradeTypes.get(i);
            long ts = timestamps.get(idx);
            double val = equity.get(idx);
            if ("BUY".equals(type)) buySeries.add(ts, val);
            else sellSeries.add(ts, val);
        }

        // 2️⃣ Создаём датасеты
        XYSeriesCollection priceDataset = new XYSeriesCollection(priceSeries);
        XYSeriesCollection equityDataset = new XYSeriesCollection(equitySeries);
        XYSeriesCollection buyDataset = new XYSeriesCollection(buySeries);
        XYSeriesCollection sellDataset = new XYSeriesCollection(sellSeries);

        // 3️⃣ Базовый график (без начального датасета)
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Ротация 50/50 | PnL: " + String.format("%.2f", equity.get(equity.size() - 1) - INITIAL_CAPITAL),
                "Время (ms)", null, null, PlotOrientation.VERTICAL, true, true, false);

        XYPlot plot = chart.getXYPlot();

        // 4️⃣ Настраиваем ДВУХ ОСЕВОЙ ШКАЛУ
        NumberAxis primaryAxis = new NumberAxis("Цена (₽)");
        primaryAxis.setLowerMargin(0.05); primaryAxis.setUpperMargin(0.05);
        NumberAxis secondaryAxis = new NumberAxis("Портфель (₽)");
        secondaryAxis.setLowerMargin(0.05); secondaryAxis.setUpperMargin(0.05);

        plot.setRangeAxis(0, primaryAxis);
        plot.setRangeAxis(1, secondaryAxis);

        // 5️⃣ Привязываем датасеты к осям
        plot.setDataset(0, priceDataset);
        plot.setDataset(1, equityDataset);
        plot.setDataset(2, buyDataset);
        plot.setDataset(3, sellDataset);

        plot.mapDatasetToRangeAxis(0, 0); // Цена -> левая ось
        plot.mapDatasetToRangeAxis(1, 1); // Портфель -> правая ось
        plot.mapDatasetToRangeAxis(2, 1); // Покупки -> правая ось
        plot.mapDatasetToRangeAxis(3, 1); // Продажи -> правая ось

        // 6️⃣ Рендереры
        XYLineAndShapeRenderer priceRenderer = new XYLineAndShapeRenderer();
        priceRenderer.setSeriesPaint(0, Color.BLUE);
        priceRenderer.setSeriesShapesVisible(0, false);

        XYLineAndShapeRenderer equityRenderer = new XYLineAndShapeRenderer();
        equityRenderer.setSeriesPaint(0, Color.GREEN);
        equityRenderer.setSeriesShapesVisible(0, false);

        XYLineAndShapeRenderer tradeRenderer = new XYLineAndShapeRenderer();
        tradeRenderer.setSeriesPaint(0, Color.RED);
        tradeRenderer.setSeriesShape(0, new java.awt.geom.Ellipse2D.Double(-4, -4, 8, 8));

        XYLineAndShapeRenderer sellRenderer = new XYLineAndShapeRenderer();
        sellRenderer.setSeriesPaint(0, Color.ORANGE);
        sellRenderer.setSeriesShape(0, new java.awt.geom.Ellipse2D.Double(-4, -4, 8, 8));

        plot.setRenderer(0, priceRenderer);
        plot.setRenderer(1, equityRenderer);
        plot.setRenderer(2, tradeRenderer);
        plot.setRenderer(3, sellRenderer);

        // 7️⃣ Экспорт в PNG
        try {
            File file = new File("backtest_dual_axis.png");
            ChartUtils.saveChartAsPNG(file, chart, 1600, 900);
            System.out.println("📊 График сохранён: " + file.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("❌ Ошибка сохранения графика: " + e.getMessage());
        }

        // 8️⃣ Отображение в GUI (EDT-safe)
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Dual-Axis Backtest: 50/50 Rebalance");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setContentPane(new ChartPanel(chart) {
                public Dimension getPreferredSize() { return new Dimension(1600, 900); }
            });
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });

        System.out.printf("📈 Итог: Total=%.2f | Cash=%.2f | Shares=%d | Сделок=%d%n",
                equity.get(equity.size() - 1), finalCash, finalShares, tradeIndices.size());
    }

}

//.setStrategyAnalysis(barSeriesManager -> {
//                                BarSeries series = barSeriesManager.getBarSeries();
//
//                                double cash = INITIAL_CAPITAL;
//                                int shares = 0;
//                                double lastPrice = 0;
//                                TradingRecord tradingRecord = new BaseTradingRecord();
//
//                                for (int i = 0; i < series.getBarCount(); i++) {
//                                    double price = series.getBar(i).getClosePrice().doubleValue();
//
//                                    if (i == series.getBarCount() - 1) {
//                                        lastPrice = price;
//                                    }
//
//                                    // 1️⃣ Изначальная покупка на 50% от стартовой суммы
//                                    if (i == 0) {
//                                        int initialBuyShares = (int) Math.floor( (INITIAL_CAPITAL * TARGET_ALLOCATION) / price);
//                                        tradingRecord.enter(i, DoubleNum.valueOf(price), DoubleNum.valueOf(initialBuyShares));
//                                        cash -= initialBuyShares * price;
//                                        shares = initialBuyShares;
//                                        continue;
//                                    }
//
//                                    // 2️⃣ Расчёт текущей стоимости портфеля и доли акций
//                                    double stockValue = shares * price;
//                                    double totalValue = cash + stockValue;
//                                    if (totalValue <= 0) continue; // Защита от деления на ноль
//
//                                    double currentAllocation = stockValue / totalValue;
//
//                                    // 3️⃣ Ребалансировка: превышение >= 51% -> продажа
//                                    if (currentAllocation >= TARGET_ALLOCATION + REBALANCE_THRESHOLD) {
//                                        double targetStockValue = totalValue * TARGET_ALLOCATION;
//                                        int sharesToSell = (int) Math.floor((stockValue - targetStockValue) / price);
//                                        if (sharesToSell > 0) {
//                                            tradingRecord.operate(i, DoubleNum.valueOf(price), DoubleNum.valueOf(-sharesToSell));
//                                            cash += sharesToSell * price;
//                                            shares -= sharesToSell;
//                                        }
//                                    }
//                                    // 4️⃣ Ребалансировка: падение <= 49% -> докупка
//                                    else if (currentAllocation <= TARGET_ALLOCATION - REBALANCE_THRESHOLD) {
//                                        double targetStockValue = totalValue * TARGET_ALLOCATION;
//                                        int sharesToBuy = (int) Math.floor((targetStockValue - stockValue) / price);
//                                        if (sharesToBuy > 0) {
//                                            tradingRecord.operate(i, DoubleNum.valueOf(price), DoubleNum.valueOf(sharesToBuy));
//                                            cash -= sharesToBuy * price;
//                                            shares += sharesToBuy;
//                                        }
//                                    }
//                                }
//
//                                double stockValue = shares * lastPrice;
//                                double totalValue = cash + stockValue;
//                                // 5️⃣ Анализ результатов
//                                var profitCriterion = new NetProfitCriterion();
////                                double profit = profitCriterion.calculate(series, tradingRecord).doubleValue();
//
//                                log.info("✅ Rebalance Backtest Complete");
////                                log.info("💰 Net Profit: {} | 📈 Final Cash: {} | 📊 Final Shares: {}",
////                                        profit, cash, shares);
//                                log.info("💰 totalValue: {} | 📈 Final Cash: {} | 📊 Final Shares: {}",
//                                        totalValue, cash, shares);
//                            })