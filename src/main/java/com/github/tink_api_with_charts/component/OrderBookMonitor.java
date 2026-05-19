package com.github.tink_api_with_charts.component;

import com.github.tink_api_with_charts.cinfiguration.TradingProperties;
import com.github.tink_api_with_charts.service.SpreadHistoryService;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import ru.tinkoff.piapi.contract.v1.Order;
import ru.tinkoff.piapi.contract.v1.OrderBook;
import ru.tinkoff.piapi.contract.v1.OrderBookType;
import ru.tinkoff.piapi.contract.v1.Quotation;
import ru.ttech.piapi.core.impl.marketdata.MarketDataStreamManager;
import ru.ttech.piapi.core.impl.marketdata.subscription.Instrument;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class OrderBookMonitor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OrderBookMonitor.class);

    public static final ZoneId ZONE_ID = ZoneId.of("Europe/Moscow");
    public static final LocalTime SESSION_1_START = LocalTime.of(7, 0, 0);
    public static final LocalTime SESSION_1_END = LocalTime.of(18, 53, 0);
    public static final LocalTime SESSION_2_START = LocalTime.of(19, 0, 1);
    public static final LocalTime SESSION_2_END = LocalTime.of(23, 49, 59);

    private final MarketDataStreamManager marketDataStreamManager;
    private final SpreadHistoryService spreadHistoryService;
    private final TradingProperties properties;

    private static final BigDecimal FUTURE_FEE_PERC = BigDecimal.valueOf(0.025);

    /**
     * Состояние по каждой паре инструментов
     * Ключ: имя пары (например, "SBER-SBERF")
     */
    private final Map<String, PairState> pairStates = new ConcurrentHashMap<>();

    /**
     * Маппинг UID инструмента на имя пары
     * Ключ: UID инструмента (акции или фьючерса)
     * Значение: имя пары
     */
    private final Map<String, String> uidToPairName = new ConcurrentHashMap<>();

    public OrderBookMonitor(MarketDataStreamManager marketDataStreamManager, 
                           SpreadHistoryService spreadHistoryService, 
                           TradingProperties properties) {
        this.marketDataStreamManager = marketDataStreamManager;
        this.spreadHistoryService = spreadHistoryService;
        this.properties = properties;
    }

//    @PostConstruct
    public void startMonitoring() {
        log.info("Запуск мониторинга стакана для инструментов");
        
        // Инициализируем пары из конфига
        Set<Instrument> instruments = new HashSet<>();
        
        if (properties.getPairs() != null && !properties.getPairs().isEmpty()) {
            // Новый формат: список пар
            for (TradingProperties.InstrumentPair pair : properties.getPairs()) {
                log.info("Добавление пары: {} (акция: {}, фьючерс: {})", 
                        pair.getName(), pair.getShareUid(), pair.getFutureUid());
                
                // Создаём состояние для пары
                PairState state = new PairState();
                pairStates.put(pair.getName(), state);
                
                // Маппим UID на имя пары
                uidToPairName.put(pair.getShareUid(), pair.getName());
                uidToPairName.put(pair.getFutureUid(), pair.getName());
                
                // Добавляем инструменты в подписку
                instruments.add(new Instrument(pair.getShareUid(), 10, OrderBookType.ORDERBOOK_TYPE_ALL));
                instruments.add(new Instrument(pair.getFutureUid(), 10, OrderBookType.ORDERBOOK_TYPE_ALL));
            }
            log.info("Всего добавлено {} пар инструментов", properties.getPairs().size());
        } else if (properties.getShareUid() != null && properties.getFutureUid() != null) {
            // Старый формат: одна пара (для обратной совместимости)
            log.warn("Используется устаревший формат конфигурации с одной парой. Рекомендуется использовать 'pairs'");
            
            String pairName = "legacy-" + properties.getShareUid();
            PairState state = new PairState();
            pairStates.put(pairName, state);
            
            uidToPairName.put(properties.getShareUid(), pairName);
            uidToPairName.put(properties.getFutureUid(), pairName);
            
            instruments.add(new Instrument(properties.getShareUid(), 10, OrderBookType.ORDERBOOK_TYPE_ALL));
            instruments.add(new Instrument(properties.getFutureUid(), 10, OrderBookType.ORDERBOOK_TYPE_ALL));
            
            log.info("Добавлена устаревшая пара: {}", pairName);
        } else {
            log.warn("Не найдено конфигурации пар инструментов. Мониторинг не запущен.");
            return;
        }
        
        // Подписываемся на стаканы
        marketDataStreamManager.subscribeOrderBooks(
                instruments,
                orderBookWrapper -> updateOrderBook(orderBookWrapper.getOriginal())
        );
        
        marketDataStreamManager.start();
        log.info("Мониторинг стакана запущен для {} инструментов", instruments.size());
    }

    private void updateOrderBook(OrderBook orderBook) {
        if (orderBook.getBidsCount() == 0 || orderBook.getAsksCount() == 0) {
            log.warn("Стакан для {} пуст", orderBook.getInstrumentUid());
            return;
        }

        Order bestBid = orderBook.getBids(0);
        Order bestAsk = orderBook.getAsks(0);

        BigDecimal bestBidPrice = quotationToBigDecimal(bestBid.getPrice());
        BigDecimal bestAskPrice = quotationToBigDecimal(bestAsk.getPrice());
        long bestBidVolume = bestBid.getQuantity();
        long bestAskVolume = bestAsk.getQuantity();

        String instrumentUid = orderBook.getInstrumentUid();
        String pairName = uidToPairName.get(instrumentUid);
        
        if (pairName == null) {
            log.warn("Получен стакан для неизвестного инструмента: {}", instrumentUid);
            return;
        }
        
        PairState state = pairStates.get(pairName);
        if (state == null) {
            log.warn("Не найдено состояние для пары: {}", pairName);
            return;
        }
        
        // Обновляем состояние пары
        state.update(instrumentUid, bestBidPrice, bestAskPrice, bestBidVolume, bestAskVolume);
        
        // Проверяем, есть ли данные по обоим инструментам пары
        if (state.hasBothInstruments()) {
            // Сохраняем спред в БД
            int futureLot = getFutureLot(pairName);
            spreadHistoryService.saveSpreadData(
                pairName,
                state.shareBid.get(), 
                state.shareAsk.get(), 
                state.futureBid.get(), 
                state.futureAsk.get(),
                futureLot
            );
            
            // Логируем спред
            logSpread(pairName, state, futureLot);
        }
    }

    /**
     * Получение размера лота фьючерса для пары
     */
    private int getFutureLot(String pairName) {
        if (properties.getPairs() != null) {
            for (TradingProperties.InstrumentPair pair : properties.getPairs()) {
                if (pair.getName().equals(pairName)) {
                    return pair.getFutureLot();
                }
            }
        }
        return 1; // значение по умолчанию
    }

    /**
     * Логирование спреда для пары
     */
    private void logSpread(String pairName, PairState state, int futureLot) {
        if (!checkTradingTime()) {
            log.debug("Торговая сессия для акций не идёт (пара: {})", pairName);
            return;
        }
        
        // Расчёт спреда с учётом комиссии
        BigDecimal spreadSell = state.futureBid.get().subtract(state.shareAsk.get());
        BigDecimal spreadBuy = state.futureAsk.get().subtract(state.shareBid.get());
        
        BigDecimal feeSell = calcFee(state.futureBid.get(), futureLot);
        BigDecimal feeBuy = calcFee(state.futureAsk.get(), futureLot);
        
        spreadSell = spreadSell.subtract(feeSell);
        spreadBuy = spreadBuy.add(feeBuy);
        
        log.info("Пара {}: Spread Sell: {} (объём: {}), Spread Buy: {} (объём: {})",
                pairName,
                spreadSell, state.shareAskQty.get(),
                spreadBuy, state.shareBidQty.get());
    }

    private @NotNull BigDecimal calcFee(BigDecimal price, int futureLot) {
        return price
                .multiply(BigDecimal.valueOf(futureLot))
                .multiply(FUTURE_FEE_PERC)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.CEILING);
    }

    private BigDecimal quotationToBigDecimal(Quotation q) {
        BigDecimal units = BigDecimal.valueOf(q.getUnits());
        BigDecimal nano = BigDecimal.valueOf(q.getNano(), 9);
        BigDecimal result = units.add(nano);
        return result.setScale(2, RoundingMode.HALF_UP);
    }

    private boolean checkTradingTime() {
        // Получаем текущее время в московском часовом поясе
        LocalTime currentTime = LocalTime.now(ZONE_ID);
        // Проверяем торговые сессии
        boolean tradingSession1 = !currentTime.isBefore(SESSION_1_START) &&
                                  !currentTime.isAfter(SESSION_1_END);
        boolean tradingSession2 = !currentTime.isBefore(SESSION_2_START) &&
                                  !currentTime.isAfter(SESSION_2_END);

        return tradingSession1 || tradingSession2;
    }

    /**
     * Состояние одной пары инструментов
     */
    private static class PairState {
        // Цены и объёмы по акции
        final AtomicReference<BigDecimal> shareBid = new AtomicReference<>(BigDecimal.ZERO);
        final AtomicReference<BigDecimal> shareAsk = new AtomicReference<>(BigDecimal.ZERO);
        final AtomicLong shareBidQty = new AtomicLong();
        final AtomicLong shareAskQty = new AtomicLong();
        
        // Цены и объёмы по фьючерсу
        final AtomicReference<BigDecimal> futureBid = new AtomicReference<>(BigDecimal.ZERO);
        final AtomicReference<BigDecimal> futureAsk = new AtomicReference<>(BigDecimal.ZERO);
        final AtomicLong futureBidQty = new AtomicLong();
        final AtomicLong futureAskQty = new AtomicLong();
        
        // Флаги наличия данных
        volatile boolean hasShareData = false;
        volatile boolean hasFutureData = false;

        /**
         * Обновление состояния по данным стакана
         */
        synchronized void update(String instrumentUid, BigDecimal bidPrice, BigDecimal askPrice, 
                                long bidQty, long askQty) {
            // Простая эвристика: если UID содержит "FUT" или заканчивается на цифру - это фьючерс
            // В реальном проекте лучше использовать явный маппинг
            if (instrumentUid.contains("FUT") || instrumentUid.matches(".*[0-9]$")) {
                futureBid.set(bidPrice);
                futureAsk.set(askPrice);
                futureBidQty.set(bidQty);
                futureAskQty.set(askQty);
                hasFutureData = true;
            } else {
                shareBid.set(bidPrice);
                shareAsk.set(askPrice);
                shareBidQty.set(bidQty);
                shareAskQty.set(askQty);
                hasShareData = true;
            }
        }

        /**
         * Проверка наличия данных по обоим инструментам
         */
        boolean hasBothInstruments() {
            return hasShareData && hasFutureData;
        }
    }
}
