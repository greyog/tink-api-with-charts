package com.github.tink_api_with_charts;

import com.github.tink_api_with_charts.component.DividendsComponent;
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
import ru.tinkoff.piapi.contract.v1.CandleInterval;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;
import ru.ttech.piapi.strategy.BacktestStrategyFactory;
import ru.ttech.piapi.strategy.candle.backtest.CandleStrategyBacktestConfiguration;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MultiAssetTmonRebalanceBacktest {

    public static final CandleInterval CANDLE_INTERVAL = CandleInterval.CANDLE_INTERVAL_15_MIN;
    public static final LocalDate DATE_FROM = LocalDate.of(2024, 1, 1);
    public static final LocalDate DATE_TO = LocalDate.of(2026, 6, 23);
    private static final Logger log = LoggerFactory.getLogger(MultiAssetTmonRebalanceBacktest.class);

    // ⚙️ ПАРАМЕТРЫ ПОРТФЕЛЯ
    private static final double INITIAL_CAPITAL = 1_000_000.0;
    private static final double TARGET_ALLOC_A = 0.30; // 50% Актив A
    //    private static final double TARGET_ALLOC_B = 0.33; // 50% Актив B
    private static final double REBALANCE_THRESHOLD = 0.005; // ±1% порог
    private static final double FEE = 0.004; // Комиссия за транзакцию
    private static final String INSTRUMENT_UID_A = "e6123145-9665-43e0-8413-cd61b8aa9b13";  // sber
    //    private static final String INSTRUMENT_UID_A = "87db07bc-0e02-4e29-90bb-05e8ef791d7b"; //T
//    private static final String INSTRUMENT_UID_B = "498ec3ff-ef27-4729-9703-a5aac48d5789"; // TMON
    private static final String INSTRUMENT_UID_B = "a240edc6-a605-44b3-9801-37b9f7c3d1ff"; // LQDT
//

    public static void main(String[] args) {
        var configuration = ConnectorConfiguration.loadPropertiesFromFile("config/application.yml");
        var backtestStrategyFactory = BacktestStrategyFactory.create(configuration);
        DividendsComponent dividendsComponent = DividendsComponent.getInstance(configuration);
        var executorService = Executors.newSingleThreadExecutor();

        try {
            // 📥 Загружаем обе серии синхронно
            BarSeries seriesA = getBarSeries(backtestStrategyFactory, INSTRUMENT_UID_A, executorService);
            BarSeries seriesB = getBarSeries(backtestStrategyFactory, INSTRUMENT_UID_B, executorService);
            System.out.println("seriesA.getBarCount() = " + seriesA.getBarCount());
            System.out.println("seriesB.getBarCount() = " + seriesB.getBarCount());
            List<Bar> alignedBarsA = new ArrayList<>();
            List<Bar> alignedBarsB = new ArrayList<>();
            alignBarSeries(seriesA, seriesB, alignedBarsA, alignedBarsB);
            Map<LocalDate, Double> dateToDividendsMap = dividendsComponent.getDateToDividendsMap(INSTRUMENT_UID_A, DATE_FROM, DATE_TO);

//            visualizeTwoAssetPrices(alignedBarsA, alignedBarsB);
            // 🔍 Запуск многоактивного симулятора
            simulateMultiAssetPortfolio(alignedBarsA, alignedBarsB, dateToDividendsMap);

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

    private static void alignBarSeries(BarSeries seriesA, BarSeries seriesB, List<Bar> alignedBarsA, List<Bar> alignedBarsB) {
        int a = 0, b = 0;
        while (a < seriesA.getBarCount() && b < seriesB.getBarCount()) {
            Bar aBar = seriesA.getBar(a);
            Bar bBar = seriesB.getBar(b);
            if (aBar.getBeginTime().isAfter(bBar.getBeginTime())) {
                b++;
            } else if (aBar.getBeginTime().isBefore(bBar.getBeginTime())) {
                a++;
            } else {
                alignedBarsA.add(aBar);
                alignedBarsB.add(bBar);
                a++;
                b++;
            }
        }
        if (alignedBarsA.size() != alignedBarsB.size()) {
            throw new IllegalStateException("📉 Серии не синхронизированы по длине: A=" + seriesA.getBarCount() + ", B=" + seriesB.getBarCount());
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

    private static void simulateMultiAssetPortfolio(List<Bar> barsA, List<Bar> barsB, Map<LocalDate, Double> dateToDividendsMap) {
        double cash = INITIAL_CAPITAL;
        int sharesA = 0, sharesB = 0;

        // 📊 Коллекторы для визуализации
        List<Long> timestamps = new ArrayList<>();
        List<Double> pricesA = new ArrayList<>();
        List<Double> pricesB = new ArrayList<>();
        List<Double> equity = new ArrayList<>();
        List<String> trades = new ArrayList<>();

        for (int i = 0; i < barsA.size(); i++) {
            double priceA = barsA.get(i).getClosePrice().doubleValue();
            double priceB = barsB.get(i).getClosePrice().doubleValue();

            Instant barInstant = barsA.get(i).getBeginTime().atZone(ZoneOffset.UTC).toInstant();
            LocalDate barDate = LocalDate.ofInstant(barInstant, ZoneOffset.UTC);
            timestamps.add(barInstant.getEpochSecond());
            pricesA.add(priceA);
            pricesB.add(priceB);

            StringBuilder tradeBuilder = new StringBuilder();
            cash += Optional.ofNullable(dateToDividendsMap.remove(barDate))
                    .map(aDouble -> {
                        tradeBuilder.append("DIVS");
                        return aDouble;
                    })
                    .orElse(0.0); // Добавляем дивиденды, если была выплата

            double valA = sharesA * priceA;
            double valB = sharesB * priceB;
            double totalValue = cash + valA + valB;
            equity.add(totalValue);

            if (totalValue <= 0) continue;
            double allocA = valA / totalValue;

            // 🔁 Ребалансировка Актив A
            if (allocA >= TARGET_ALLOC_A + REBALANCE_THRESHOLD) {
                double targetValA = totalValue * TARGET_ALLOC_A;
                int toSellA = (int) Math.floor((valA - targetValA) / priceA);
                if (toSellA > 0) {
                    cash += (toSellA * priceA) * (1 - FEE);
                    sharesA -= toSellA;
                    tradeBuilder.append("SELL_A");
                }
            } else if (allocA <= TARGET_ALLOC_A - REBALANCE_THRESHOLD) {
                double targetValA = totalValue * TARGET_ALLOC_A;
                int toBuyA = (int) Math.floor((targetValA - valA) / priceA);
                if (toBuyA > 0) {
                    cash -= (toBuyA * priceA * (1 + FEE));
                    sharesA += toBuyA;
                    tradeBuilder.append("BUY_A");
                }
            }
            if (cash < 0) { // продаём Фонд ликвидности, чтобы не брать плечо
                int toSellB = (int) Math.ceil((-cash) / priceB);
                cash += (toSellB * priceB) * (1 - FEE);
                sharesB -= toSellB;
            } else if (cash > 0) { // покупаем Фонд ликвидности на свободные средства
                int toBuyB = (int) Math.floor(cash / priceB);
                cash -= (toBuyB * priceB * (1 - FEE));
                sharesB += toBuyB;
            }
            trades.add(tradeBuilder.toString());
        }

        long tradesCount = trades.stream()
                .filter(s -> !s.isBlank())
                .count();

        double finalEquity = equity.isEmpty() ? INITIAL_CAPITAL : equity.getLast();
        double netPnl = finalEquity - INITIAL_CAPITAL;
        double returnPercent = (finalEquity / INITIAL_CAPITAL - 1) * 100;
        double valueA = sharesA * pricesA.getLast();
        double valueB = sharesB * pricesB.getLast();

        log.info("✅ Multi-Asset Rebalance Complete");
        LocalDate actualStartDate = LocalDate.ofInstant(Instant.ofEpochSecond(timestamps.getFirst()), ZoneOffset.UTC);
        log.info("actualStartDate = {}", actualStartDate);
        log.info("💰 Net PnL: {} | 📈 Return: {}% | 📦 Trades: {}",
                String.format("%.2f", netPnl),
                String.format("%.2f", returnPercent),
                tradesCount);
        log.info("📊 Final: Cash={}, A={}₽, B={}₽",
                String.format("%.2f", cash),
                String.format("%.2f", valueA),
                String.format("%.2f", valueB));

        visualizeMultiAsset(timestamps, barsA, barsB, equity, trades);
    }

    private static void visualizeMultiAsset(List<Long> timestamps, List<Bar> barsA, List<Bar> barsB,
                                            List<Double> equity, List<String> trades) {
        XYSeries priceSeriesA = new XYSeries("Актив A");
        XYSeries priceSeriesB = new XYSeries("Актив B");
        XYSeries equitySeries = new XYSeries("Портфель");
        XYSeries buySeries = new XYSeries("Покупки");
        XYSeries sellSeries = new XYSeries("Продажи");
        XYSeries dividendSeries = new XYSeries("Дивиденды");

        for (int i = 0; i < barsA.size(); i++) {
            Bar aBar = barsA.get(i);
            Bar bBar = barsB.get(i);
            long second = timestamps.get(i);
            priceSeriesA.add(second, aBar.getClosePrice().bigDecimalValue());
            priceSeriesB.add(second, bBar.getClosePrice().bigDecimalValue());
            equitySeries.add(second, equity.get(i));
            String t = trades.get(i);
            if (t.contains("BUY")) buySeries.add(second, equity.get(i));
            else if (t.contains("SELL")) sellSeries.add(second, equity.get(i));
            if (t.contains("DIVS")) {
                dividendSeries.add(second, equity.get(i));
            }
        }

        var dataset = new XYSeriesCollection();
        dataset.addSeries(priceSeriesA);
        dataset.addSeries(priceSeriesB);
        dataset.addSeries(equitySeries);
        dataset.addSeries(buySeries);
        dataset.addSeries(sellSeries);
        dataset.addSeries(dividendSeries);

        JFreeChart chart = ChartFactory.createXYLineChart(
                "Multi-Asset 50/50 | PnL: " + String.format("%.2f", equity.getLast() - INITIAL_CAPITAL),
                "Время (s)", "Цена/Портфель (₽)", dataset,
                PlotOrientation.VERTICAL, true, true, false);

        XYPlot plot = chart.getXYPlot();
        NumberAxis primaryAxis = new NumberAxis("Цена/Акции (₽)");
        primaryAxis.setAutoRangeIncludesZero(false);
        NumberAxis secondaryAxis = new NumberAxis("Стоимость портфеля (₽)");
        secondaryAxis.setAutoRangeIncludesZero(false);
        plot.setRangeAxis(0, primaryAxis);
        plot.setRangeAxis(1, secondaryAxis);

        plot.setDataset(0, new XYSeriesCollection(priceSeriesA));
        plot.setDataset(1, new XYSeriesCollection(priceSeriesB));
        plot.setDataset(2, new XYSeriesCollection(equitySeries));
        plot.setDataset(3, new XYSeriesCollection(buySeries));
        plot.setDataset(4, new XYSeriesCollection(sellSeries));
        plot.setDataset(5, new XYSeriesCollection(dividendSeries));

        plot.mapDatasetToRangeAxis(0, 0);
        plot.mapDatasetToRangeAxis(1, 0);
        plot.mapDatasetToRangeAxis(2, 1);
        plot.mapDatasetToRangeAxis(3, 1);
        plot.mapDatasetToRangeAxis(4, 1);
        plot.mapDatasetToRangeAxis(5, 1);

        XYLineAndShapeRenderer prA = new XYLineAndShapeRenderer(); prA.setSeriesPaint(0, Color.BLUE);
// Создаем маленький круг (диаметр 6 пикселей, смещенный на -3, чтобы центр был в точке данных)
        prA.setSeriesShape(0, new Ellipse2D.Double(-1.0, -1.0, 1.0, 1.0));
        XYLineAndShapeRenderer prB = new XYLineAndShapeRenderer(); prB.setSeriesPaint(0, Color.MAGENTA);
        XYLineAndShapeRenderer prE = new XYLineAndShapeRenderer(); prE.setSeriesPaint(0, Color.CYAN);
        prE.setSeriesShape(0, new Ellipse2D.Double(-1.0, -1.0, 0.5, 0.5));
        XYLineAndShapeRenderer trB = new XYLineAndShapeRenderer(); trB.setSeriesPaint(0, Color.GREEN);
        trB.setSeriesShape(0, createLetterShape('B', 12));
        XYLineAndShapeRenderer trS = new XYLineAndShapeRenderer(); trS.setSeriesPaint(0, Color.RED);
        trS.setSeriesShape(0, createLetterShape('S', 12));
        XYLineAndShapeRenderer trD = new XYLineAndShapeRenderer(); trD.setSeriesPaint(0, Color.PINK);
        trD.setSeriesShape(0, createLetterShape('D', 12));

        plot.setRenderer(0, prA);
        plot.setRenderer(1, prB);
        plot.setRenderer(2, prE);
        plot.setRenderer(3, trB);
        plot.setRenderer(4, trS);
        plot.setRenderer(5, trD);

        try {
            ChartUtils.saveChartAsPNG(new File("backtest_multiasset_lqdt.png"), chart, 1600, 900);
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

    private static void visualizeTwoAssetPrices(List<Bar> barsA, List<Bar> barsB) {
        XYSeries priceSeriesA = new XYSeries("Актив A");
        XYSeries priceSeriesB = new XYSeries("Актив B");

        for (int i = 0; i < barsA.size(); i++) {
            Bar aBar = barsA.get(i);
            Bar bBar = barsB.get(i);
            long second = aBar.getBeginTime().getEpochSecond();
            priceSeriesA.add(second, aBar.getClosePrice().bigDecimalValue());
            priceSeriesB.add(second, bBar.getClosePrice().bigDecimalValue());
        }

        var dataset = new XYSeriesCollection();
        dataset.addSeries(priceSeriesA);
        dataset.addSeries(priceSeriesB);

        JFreeChart chart = ChartFactory.createXYLineChart(
                "Multi-Asset 50/50",
                "Время (s)", "Цена (₽)", dataset,
                PlotOrientation.VERTICAL, true, true, false);

        XYPlot plot = chart.getXYPlot();
        NumberAxis primaryAxis = new NumberAxis("Цена/Акции A (₽)");
        NumberAxis secondaryAxis = new NumberAxis("Цена/Акции B (₽)");
        plot.setRangeAxis(0, primaryAxis);
        plot.setRangeAxis(1, secondaryAxis);

        plot.setDataset(0, new XYSeriesCollection(priceSeriesA));
        plot.setDataset(1, new XYSeriesCollection(priceSeriesB));

        plot.mapDatasetToRangeAxis(0, 0);
        plot.mapDatasetToRangeAxis(1, 1);

        XYLineAndShapeRenderer prA = new XYLineAndShapeRenderer(); prA.setSeriesPaint(0, Color.BLUE);
        XYLineAndShapeRenderer prB = new XYLineAndShapeRenderer(); prB.setSeriesPaint(0, Color.MAGENTA);

        plot.setRenderer(0, prA); plot.setRenderer(1, prB);

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

    // Метод для создания Shape из буквы
    public static Shape createLetterShape(char letter, int fontSize) {
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
        FontRenderContext frc = new FontRenderContext(new AffineTransform(), true, true);
        TextLayout layout = new TextLayout(String.valueOf(letter), font, frc);
        return layout.getOutline(null);
    }

}