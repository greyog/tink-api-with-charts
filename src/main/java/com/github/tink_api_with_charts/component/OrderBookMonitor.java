package com.github.tink_api_with_charts.component;

import com.github.tink_api_with_charts.cinfiguration.TradingProperties;
import com.github.tink_api_with_charts.entity.PairState;
import com.github.tink_api_with_charts.service.SpreadHistoryService;
import com.github.tink_api_with_charts.utils.NumberUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import ru.tinkoff.piapi.contract.v1.InstrumentType;
import ru.tinkoff.piapi.contract.v1.Order;
import ru.tinkoff.piapi.contract.v1.OrderBook;
import ru.tinkoff.piapi.contract.v1.OrderBookType;
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

    private static final BigDecimal FUTURE_FEE_FRAC = BigDecimal.valueOf(0.025).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
    private static final BigDecimal SHARE_FEE_FRAC = BigDecimal.valueOf(0.04).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);

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
    private final Map<String, Integer>  pairLots = new ConcurrentHashMap<>();
    private final Set<String> futureUids = new HashSet<>();
    private final Set<String> shareUids = new HashSet<>();

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

                pairLots.put(pair.getName(), pair.getFutureLot());
                futureUids.add(pair.getFutureUid());
                shareUids.add(pair.getShareUid());
                
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

        BigDecimal bestBidPrice = NumberUtils.quotationToBigDecimal(bestBid.getPrice());
        BigDecimal bestAskPrice = NumberUtils.quotationToBigDecimal(bestAsk.getPrice());
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

        if (futureUids.contains(instrumentUid)) {
            state.update(InstrumentType.INSTRUMENT_TYPE_FUTURES, bestBidPrice, bestAskPrice, bestBidVolume, bestAskVolume);
        } else {
            state.update(InstrumentType.INSTRUMENT_TYPE_SHARE, bestBidPrice, bestAskPrice, bestBidVolume, bestAskVolume);
        }

        // Проверяем, есть ли данные по обоим инструментам пары
        if (state.hasBothInstruments()) {
            // Сохраняем спред в БД
            int futureLot = pairLots.get(pairName);
            spreadHistoryService.saveSpreadData(
                pairName,
                state.getShareBid(),
                state.getShareAsk(),
                state.getFutureBid(),
                state.getFutureAsk(),
                futureLot
            );
            
            // Логируем спред
            logSpread(pairName, state, futureLot);
        }
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
        BigDecimal spreadSell = state.getFutureBid().subtract(state.getShareAsk());
        BigDecimal spreadBuy = state.getFutureAsk().subtract(state.getShareBid());
        
        BigDecimal feeSellFuture = calcFutureFee(state.getFutureBid(), 1);
        BigDecimal feeBuyShare = calcShareFee(state.getShareAsk(), futureLot);
        BigDecimal feeBuyFuture = calcFutureFee(state.getFutureAsk(), 1);
        BigDecimal feeSellShare = calcShareFee(state.getShareBid(), futureLot);
        
        spreadSell = spreadSell.subtract(feeSellFuture).subtract(feeBuyShare);
        spreadBuy = spreadBuy.add(feeBuyFuture).add(feeSellShare);
        
        log.info("Пара {}: Spread Sell: {}, Spread Buy: {}",
                pairName,
                spreadSell,
                spreadBuy);
    }

    private @NotNull BigDecimal calcFutureFee(BigDecimal price, int lot) {
        return price
                .multiply(BigDecimal.valueOf(lot))
                .multiply(FUTURE_FEE_FRAC);
    }

    private @NotNull BigDecimal calcShareFee(BigDecimal price, int lot) {
        return price
                .multiply(BigDecimal.valueOf(lot))
                .multiply(SHARE_FEE_FRAC);
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

}
