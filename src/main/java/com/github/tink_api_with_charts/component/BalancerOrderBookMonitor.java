package com.github.tink_api_with_charts.component;

import com.github.tink_api_with_charts.cinfiguration.BalancerProperties;
import com.github.tink_api_with_charts.service.BalancerStateService;
import jakarta.annotation.PostConstruct;
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
import java.util.Set;

@Component
public class BalancerOrderBookMonitor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BalancerOrderBookMonitor.class);

    public static final ZoneId ZONE_ID = ZoneId.of("Europe/Moscow");
    public static final LocalTime SESSION_1_START = LocalTime.of(7, 0, 0);
    public static final LocalTime SESSION_1_END = LocalTime.of(18, 53, 0);
    public static final LocalTime SESSION_2_START = LocalTime.of(19, 0, 1);
    public static final LocalTime SESSION_2_END = LocalTime.of(23, 49, 59);

    private final MarketDataStreamManager marketDataStreamManager;
    private final BalancerProperties properties;
    private final BalancerStateService balancerStateService;

    private static final double FEE = 0.0;

    public BalancerOrderBookMonitor(MarketDataStreamManager marketDataStreamManager,
                                    BalancerProperties properties, BalancerStateService balancerStateService) {
        this.marketDataStreamManager = marketDataStreamManager;
        this.properties = properties;
        this.balancerStateService = balancerStateService;
    }

    @PostConstruct
    public void startMonitoring() {
        log.info("Запуск мониторинга стакана для инструментов");
        
        // Инициализируем пары из конфига
        Set<Instrument> instruments = new HashSet<>();
        instruments.add(new Instrument(properties.getShareUid(), 10, OrderBookType.ORDERBOOK_TYPE_ALL));
        instruments.add(new Instrument(properties.getCashEtfUid(), 10, OrderBookType.ORDERBOOK_TYPE_ALL));
        
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
            log.warn("Стакан для {} пуст", orderBook.getTicker());
            return;
        }

        Order bestBid = orderBook.getBids(0);
        Order bestAsk = orderBook.getAsks(0);

        BigDecimal bestBidPrice = quotationToBigDecimal(bestBid.getPrice());
        BigDecimal bestAskPrice = quotationToBigDecimal(bestAsk.getPrice());
        long bestBidQty = bestBid.getQuantity();
        long bestAskQty = bestAsk.getQuantity();

        String instrumentUid = orderBook.getInstrumentUid();
        if (instrumentUid.equals(properties.getShareUid())) {
            balancerStateService.updateSharePrice(bestBidPrice, bestAskPrice);
        } else if (instrumentUid.equals(properties.getCashEtfUid())) {
            balancerStateService.updateCashEtfPrice(bestBidPrice, bestAskPrice);
        } else {
            log.warn("Unknown instrument UID: {}", instrumentUid);
        }
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

}
