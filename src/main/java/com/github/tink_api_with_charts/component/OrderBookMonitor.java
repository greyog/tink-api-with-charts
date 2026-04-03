package com.github.tink_api_with_charts.component;

import com.github.tink_api_with_charts.cinfiguration.TradingProperties;
import com.github.tink_api_with_charts.service.SpreadHistoryService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import ru.tinkoff.piapi.contract.v1.Order;
import ru.tinkoff.piapi.contract.v1.OrderBook;
import ru.tinkoff.piapi.contract.v1.OrderBookType;
import ru.tinkoff.piapi.contract.v1.Quotation;
import ru.tinkoff.piapi.contract.v1.TradeDirection;
import ru.tinkoff.piapi.contract.v1.TradeSourceType;
import ru.ttech.piapi.core.impl.marketdata.MarketDataStreamManager;
import ru.ttech.piapi.core.impl.marketdata.subscription.Instrument;
import ru.ttech.piapi.core.impl.marketdata.wrapper.TradeWrapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderBookMonitor {

    public static final ZoneId ZONE_ID = ZoneId.of("Europe/Moscow");
    public static final LocalTime SESSION_1_START = LocalTime.of(7, 0, 0);
    public static final LocalTime SESSION_1_END = LocalTime.of(18, 53, 0);
    public static final LocalTime SESSION_2_START = LocalTime.of(19, 0, 1);
    public static final LocalTime SESSION_2_END = LocalTime.of(23, 49, 59);

    private final MarketDataStreamManager marketDataStreamManager;
    private final SpreadHistoryService spreadHistoryService;
    private final TradingProperties properties;

    private static final BigDecimal FUTURE_FEE_PERC = BigDecimal.valueOf(0.025);

    // Текущие значения стакана
    private static final AtomicReference<BigDecimal> shareBid = new AtomicReference<>(BigDecimal.ZERO);
    private static final AtomicReference<BigDecimal> shareAsk = new AtomicReference<>(BigDecimal.ZERO);
    private static final AtomicReference<BigDecimal> futureBid = new AtomicReference<>(BigDecimal.ZERO);
    private static final AtomicReference<BigDecimal> futureAsk = new AtomicReference<>(BigDecimal.ZERO);

    private static final AtomicReference<BigDecimal> futureLastBuyPrice = new AtomicReference<>(BigDecimal.ZERO);
    private static final AtomicReference<BigDecimal> futureLastSellPrice = new AtomicReference<>(BigDecimal.ZERO);

    private static final AtomicLong shareBidQty = new AtomicLong();
    private static final AtomicLong shareAskQty = new AtomicLong();
    private static final AtomicLong futureBidQty = new AtomicLong();
    private static final AtomicLong futureAskQty = new AtomicLong();

    // Последние сохраненные значения спреда
    private static final AtomicReference<BigDecimal> lastSpreadSell = new AtomicReference<>(BigDecimal.ZERO);
    private static final AtomicReference<BigDecimal> lastSpreadBuy = new AtomicReference<>(BigDecimal.ZERO);
    private static final AtomicLong lastSpreadSellQty = new AtomicLong();
    private static final AtomicLong lastSpreadBuyQty = new AtomicLong();

    @PostConstruct
    public void startMonitoring() {
        log.info("Запуск мониторинга стакана для инструментов");
        marketDataStreamManager.subscribeOrderBooks(
                Set.of(
                        new Instrument(properties.getShareUid(), 10, OrderBookType.ORDERBOOK_TYPE_ALL),
                        new Instrument(properties.getFutureUid(), 10, OrderBookType.ORDERBOOK_TYPE_ALL)
                ),
                orderBookWrapper -> updateOrderBook(orderBookWrapper.getOriginal())
        );
        marketDataStreamManager.subscribeTrades(Set.of(new Instrument(properties.getFutureUid())),
                TradeSourceType.TRADE_SOURCE_ALL, true,
                tradeWrapper -> updateLastPrice(tradeWrapper),
                openInterestWrapper -> log.info("Open Interest for {}: {}", openInterestWrapper.getTicker(), openInterestWrapper.getOpenInterest()));
        marketDataStreamManager.start();
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
        if (properties.getShareUid().equals(instrumentUid)) {
            shareBid.set(bestBidPrice);
            shareAsk.set(bestAskPrice);
            shareBidQty.set(bestBidVolume);
            shareAskQty.set(bestAskVolume);
        } else if (properties.getFutureUid().equals(instrumentUid)) {
            futureBid.set(bestBidPrice);
            futureAsk.set(bestAskPrice);
            futureBidQty.set(bestBidVolume);
            futureAskQty.set(bestAskVolume);
        }
    }

    private void updateLastPrice(TradeWrapper trade) {
        switch (trade.getDirection()) {
            case TRADE_DIRECTION_BUY -> {
                futureLastBuyPrice.set(trade.getPrice());
            }
            case TRADE_DIRECTION_SELL -> {
                futureLastSellPrice.set(trade.getPrice());
            }
            default -> {log.info("Неизвестный trade direction: {}", trade.getDirection()); return;}
        }
        updateSpreads(trade.getDirection());
    }

    private void updateSpreads(TradeDirection direction) {
        if (shareBidQty.get() * shareAskQty.get() * futureBidQty.get() * futureAskQty.get() * futureLastSellPrice.get().signum() * futureLastBuyPrice.get().signum() == 0) {
            log.info("Не хватает данных для расчёта цены раздвижки");
            return;
        }

        switch (direction) {
            case TRADE_DIRECTION_BUY -> {
                BigDecimal spreadRawSell = futureLastBuyPrice.get().subtract(shareAsk.get());
                BigDecimal fee = calcFee(futureLastBuyPrice.get());
                lastSpreadSell.set(spreadRawSell.subtract(fee));
            }
            case TRADE_DIRECTION_SELL -> {
                BigDecimal spreadRawBuy = futureLastSellPrice.get().subtract(shareBid.get());
                BigDecimal fee = calcFee(futureLastSellPrice.get());
                lastSpreadBuy.set(spreadRawBuy.add(fee));
            }
        }

//        long spreadSellQty = Math.min(futureBidQty.get(), shareAskQty.get());
//        long spreadBuyQty = Math.min(futureAskQty.get(), shareBidQty.get());

        // Проверяем, изменились ли значения
//        boolean hasChanged = lastSpreadSell.get().compareTo(spreadSell) != 0 ||
//                             lastSpreadBuy.get().compareTo(spreadBuy) != 0;

//        if (hasChanged) {
        // Обновляем последние значения
//            lastSpreadSell.set(spreadSell);
//            lastSpreadBuy.set(spreadBuy);
//            lastSpreadSellQty.set(spreadSellQty);
//            lastSpreadBuyQty.set(spreadBuyQty);
        if (!checkTradingTime()) {
            log.info("Торговая сессия для акций не идёт");
            return;
        }
        // Выводим в консоль
        log.info("Spread Sell: {} ({}), Spread Buy: {} ({})",
                lastSpreadSell.get(), 0,
                lastSpreadBuy.get(), 0);
        if (lastSpreadSell.get().signum() * lastSpreadBuy.get().signum() == 0) {
            log.info("Нули не сохраняем");
            return;
        }

        // Сохраняем в БД через сервис
        spreadHistoryService.saveSpreadData(lastSpreadSell.get(), lastSpreadBuy.get(), 0, 0);
//        }
    }

    private @NotNull BigDecimal calcFee(BigDecimal price) {
        return price
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
}