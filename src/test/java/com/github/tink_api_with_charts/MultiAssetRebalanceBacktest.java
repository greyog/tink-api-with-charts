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
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.analysis.cost.LinearTransactionCostModel;
import org.ta4j.core.backtest.BarSeriesManager;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MultiAssetRebalanceBacktest {

    public static final CandleInterval CANDLE_INTERVAL = CandleInterval.CANDLE_INTERVAL_5_MIN;
    public static final LocalDate DATE_FROM = LocalDate.of(2025, 1, 1);
    public static final LocalDate DATE_TO = LocalDate.of(2026, 6, 23);
    private static final Logger log = LoggerFactory.getLogger(MultiAssetRebalanceBacktest.class);

    // ⚙️ ПАРАМЕТРЫ ПОРТФЕЛЯ
    private static final double INITIAL_CAPITAL = 1_000_000.0;
    private static final double TARGET_ALLOC_A = 0.50; // 50% Актив A
    private static final double TARGET_ALLOC_B = 0.50; // 50% Актив B
    private static final double REBALANCE_THRESHOLD = 0.01; // ±1% порог
    private static final String INSTRUMENT_UID_A = "87db07bc-0e02-4e29-90bb-05e8ef791d7b"; // ваш uid
    private static final String INSTRUMENT_UID_B = "498ec3ff-ef27-4729-9703-a5aac48d5789";                // второй uid

    public static void main(String[] args) {
        var configuration = ConnectorConfiguration.loadPropertiesFromFile("config/application.yml");
        var backtestStrategyFactory = BacktestStrategyFactory.create(configuration);
        var executorService = Executors.newSingleThreadExecutor();

        try {
            // 📥 Загружаем обе серии синхронно
            BarSeries seriesA = getBarSeries(backtestStrategyFactory, INSTRUMENT_UID_A, executorService);
            BarSeries seriesB = getBarSeries(backtestStrategyFactory, INSTRUMENT_UID_B, executorService);
            System.out.println("seriesA.getBarCount() = " + seriesA.getBarCount());
            System.out.println("seriesB.getBarCount() = " + seriesB.getBarCount());
            int a = 0, b = 0;
            while (a < seriesA.getBarCount() && b < seriesB.getBarCount()) {
                Bar aBar = seriesA.getBar(a);
                Bar bBar = seriesB.getBar(b);
                a++;
                b++;
            }
            if (seriesA.getBarCount() != seriesB.getBarCount()) {
                throw new IllegalStateException("📉 Серии не синхронизированы по длине: A=" + seriesA.getBarCount() + ", B=" + seriesB.getBarCount());
            }

            // 🔍 Запуск многоактивного симулятора
//            simulateMultiAssetPortfolio(seriesA, seriesB);

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

    private static BarSeries getBarSeries(BacktestStrategyFactory backtestStrategyFactory, String instrumentUid, ExecutorService executorService) {
        BarSeriesManager[] seriesAArray = new BarSeriesManager[1];
        backtestStrategyFactory.newCandleStrategyBacktest(
                CandleStrategyBacktestConfiguration.builder()
                        .setInstrumentId(instrumentUid)
                        .setCandleInterval(CANDLE_INTERVAL)
                        .setFrom(DATE_FROM)
                        .setTo(DATE_TO)
                        .setTradeExecutionModel(new TradeOnCurrentCloseModel())
                        .setTradeFeeModel(new LinearTransactionCostModel(0.0025))
                        .setExecutorService(executorService)
                        .setStrategyAnalysis(barSeriesManager ->
                                seriesAArray[0] = barSeriesManager)
                        .build())
                .run();
        BarSeries seriesA = seriesAArray[0].getBarSeries();
        return seriesA;
    }

    private static void simulateMultiAssetPortfolio(BarSeries seriesA, BarSeries seriesB) {
        NumFactory numFactory = seriesA.numFactory();
        double cash = INITIAL_CAPITAL;
        int sharesA = 0, sharesB = 0;
        
        // 📊 Коллекторы для визуализации
        List<Long> timestamps = new ArrayList<>();
        List<Double> pricesA = new ArrayList<>();
        List<Double> pricesB = new ArrayList<>();
        List<Double> equity = new ArrayList<>();
        List<String> trades = new ArrayList<>();

        for (int i = 0; i < seriesA.getBarCount(); i++) {
            double priceA = seriesA.getBar(i).getClosePrice().doubleValue();
            double priceB = seriesB.getBar(i).getClosePrice().doubleValue();
            
            timestamps.add(seriesA.getBar(i).getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            pricesA.add(priceA);
            pricesB.add(priceB);

            double valA = sharesA * priceA;
            double valB = sharesB * priceB;
            double totalValue = cash + valA + valB;
            equity.add(totalValue);

            if (totalValue <= 0) continue;
            double allocA = valA / totalValue;
            double allocB = valB / totalValue;

            // 🔁 Ребалансировка Актив A
            if (allocA >= TARGET_ALLOC_A + REBALANCE_THRESHOLD) {
                double targetValA = totalValue * TARGET_ALLOC_A;
                int toSellA = (int) Math.floor((valA - targetValA) / priceA);
                if (toSellA > 0) {
                    cash += toSellA * priceA;
                    sharesA -= toSellA;
                    trades.add("SELL_A");
                }
            } else if (allocA <= TARGET_ALLOC_A - REBALANCE_THRESHOLD) {
                double targetValA = totalValue * TARGET_ALLOC_A;
                int toBuyA = (int) Math.floor((targetValA - valA) / priceA);
                if (toBuyA > 0) {
                    cash -= toBuyA * priceA;
                    sharesA += toBuyA;
                    trades.add("BUY_A");
                }
            }

            // 🔁 Ребалансировка Актив B
            if (allocB >= TARGET_ALLOC_B + REBALANCE_THRESHOLD) {
                double targetValB = totalValue * TARGET_ALLOC_B;
                int toSellB = (int) Math.floor((valB - targetValB) / priceB);
                if (toSellB > 0) {
                    cash += toSellB * priceB;
                    sharesB -= toSellB;
                    trades.add("SELL_B");
                }
            } else if (allocB <= TARGET_ALLOC_B - REBALANCE_THRESHOLD) {
                double targetValB = totalValue * TARGET_ALLOC_B;
                int toBuyB = (int) Math.floor((targetValB - valB) / priceB);
                if (toBuyB > 0) {
                    cash -= toBuyB * priceB;
                    sharesB += toBuyB;
                    trades.add("BUY_B");
                }
            }
        }

        double finalEquity = equity.isEmpty() ? INITIAL_CAPITAL : equity.get(equity.size() - 1);
        log.info("✅ Multi-Asset Rebalance Complete");
        log.info("💰 Net PnL: {:.2f} | 📈 Return: {:.2f}% | 📦 Trades: {}",
                finalEquity - INITIAL_CAPITAL, (finalEquity / INITIAL_CAPITAL - 1) * 100, trades.size());
        log.info("📊 Final: Cash={:.2f}, A={:.2f}₽, B={:.2f}₽", cash, sharesA * pricesA.get(pricesA.size()-1), sharesB * pricesB.get(pricesB.size()-1));

        visualizeMultiAsset(timestamps, pricesA, pricesB, equity, trades);
    }

    private static void visualizeMultiAsset(List<Long> timestamps, List<Double> pricesA, List<Double> pricesB,
                                            List<Double> equity, List<String> trades) {
        XYSeries priceSeriesA = new XYSeries("Актив A");
        XYSeries priceSeriesB = new XYSeries("Актив B");
        XYSeries equitySeries = new XYSeries("Портфель");
        XYSeries buySeries = new XYSeries("Покупки");
        XYSeries sellSeries = new XYSeries("Продажи");

        for (int i = 0; i < timestamps.size(); i++) {
            long ts = timestamps.get(i);
            priceSeriesA.add(ts, pricesA.get(i));
            priceSeriesB.add(ts, pricesB.get(i));
            equitySeries.add(ts, equity.get(i));
            String t = trades.get(i);
            if (t.contains("BUY")) buySeries.add(ts, equity.get(i));
            else if (t.contains("SELL")) sellSeries.add(ts, equity.get(i));
        }

        var dataset = new XYSeriesCollection();
        dataset.addSeries(priceSeriesA);
        dataset.addSeries(priceSeriesB);
        dataset.addSeries(equitySeries);
        dataset.addSeries(buySeries);
        dataset.addSeries(sellSeries);

        JFreeChart chart = ChartFactory.createXYLineChart(
            "Multi-Asset 50/50 | PnL: " + String.format("%.2f", equity.get(equity.size()-1) - INITIAL_CAPITAL),
            "Время (ms)", "Цена/Портфель (₽)", dataset,
            PlotOrientation.VERTICAL, true, true, false);

        XYPlot plot = chart.getXYPlot();
        NumberAxis primaryAxis = new NumberAxis("Цена/Акции (₽)");
        NumberAxis secondaryAxis = new NumberAxis("Стоимость портфеля (₽)");
        plot.setRangeAxis(0, primaryAxis);
        plot.setRangeAxis(1, secondaryAxis);

        plot.setDataset(0, new XYSeriesCollection(priceSeriesA));
        plot.setDataset(1, new XYSeriesCollection(priceSeriesB));
        plot.setDataset(2, new XYSeriesCollection(equitySeries));
        plot.setDataset(3, new XYSeriesCollection(buySeries));
        plot.setDataset(4, new XYSeriesCollection(sellSeries));

        plot.mapDatasetToRangeAxis(0, 0);
        plot.mapDatasetToRangeAxis(1, 0);
        plot.mapDatasetToRangeAxis(2, 1);
        plot.mapDatasetToRangeAxis(3, 1);
        plot.mapDatasetToRangeAxis(4, 1);

        XYLineAndShapeRenderer prA = new XYLineAndShapeRenderer(); prA.setSeriesPaint(0, Color.BLUE);
        XYLineAndShapeRenderer prB = new XYLineAndShapeRenderer(); prB.setSeriesPaint(0, Color.MAGENTA);
        XYLineAndShapeRenderer prE = new XYLineAndShapeRenderer(); prE.setSeriesPaint(0, Color.GREEN);
        XYLineAndShapeRenderer trB = new XYLineAndShapeRenderer(); trB.setSeriesPaint(0, Color.RED);
        XYLineAndShapeRenderer trS = new XYLineAndShapeRenderer(); trS.setSeriesPaint(0, Color.ORANGE);

        plot.setRenderer(0, prA); plot.setRenderer(1, prB); plot.setRenderer(2, prE);
        plot.setRenderer(3, trB); plot.setRenderer(4, trS);

        try {
            ChartUtils.saveChartAsPNG(new File("backtest_multiasset.png"), chart, 1600, 900);
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Multi-Asset Rebalance");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setContentPane(new ChartPanel(chart) {
                public Dimension getPreferredSize() { return new Dimension(1600, 900); }
            });
            frame.pack(); frame.setLocationRelativeTo(null); frame.setVisible(true);
        });
    }
}