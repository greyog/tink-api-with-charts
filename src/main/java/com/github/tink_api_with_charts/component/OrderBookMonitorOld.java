package com.github.tink_api_with_charts.component;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.tinkoff.piapi.contract.v1.Order;
import ru.tinkoff.piapi.contract.v1.OrderBook;
import ru.tinkoff.piapi.contract.v1.OrderBookType;
import ru.tinkoff.piapi.contract.v1.Quotation;
import ru.ttech.piapi.core.impl.marketdata.MarketDataStreamManager;
import ru.ttech.piapi.core.impl.marketdata.subscription.Instrument;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class OrderBookMonitorOld {

    private final MarketDataStreamManager marketDataStreamManager;

    // Идентификатор инструмента (figi или ticker)
    // T,TCS80A107UL4,87db07bc-0e02-4e29-90bb-05e8ef791d7b
    // T,	 TBM6,	 FUTT06260000,	 12275713-0583-4add-a4a5-c7dd234d4f34
    private static final String SHARE_UID = "87db07bc-0e02-4e29-90bb-05e8ef791d7b";
    private static final String FUTURE_UID = "12275713-0583-4add-a4a5-c7dd234d4f34";

    private static final AtomicReference<BigDecimal> shareBid = new AtomicReference<>(BigDecimal.ZERO);
    private static final AtomicReference<BigDecimal> shareAsk = new AtomicReference<>(BigDecimal.ZERO);
    private static final AtomicReference<BigDecimal> futureBid = new AtomicReference<>(BigDecimal.ZERO);
    private static final AtomicReference<BigDecimal> futureAsk = new AtomicReference<>(BigDecimal.ZERO);

    private static final AtomicLong shareBidQty = new AtomicLong();
    private static final AtomicLong shareAskQty = new AtomicLong();
    private static final AtomicLong futureBidQty = new AtomicLong();
    private static final AtomicLong futureAskQty = new AtomicLong();

    public OrderBookMonitorOld(
            MarketDataStreamManager marketDataStreamManager
    ) {
        this.marketDataStreamManager = marketDataStreamManager;
    }

//        @PostConstruct
    public void startMonitoring() {
        log.info("Запуск мониторинга стакана для INSTRUMENT_UID={}", SHARE_UID);

        marketDataStreamManager.subscribeOrderBooks(
                Set.of(
                        new Instrument(SHARE_UID, 10, OrderBookType.ORDERBOOK_TYPE_ALL),
                        new Instrument(FUTURE_UID, 10, OrderBookType.ORDERBOOK_TYPE_ALL)
                ),
                orderBookWrapper -> {
                    updateOrderBook(orderBookWrapper.getOriginal());
//                    printBestBidAndAsk(orderBookWrapper.getOriginal());
                });
        marketDataStreamManager.start();
    }

    private void updateOrderBook(OrderBook orderBook) {
        if (orderBook.getBidsCount() == 0 || orderBook.getAsksCount() == 0) {
            log.warn("Стакан для {} пуст", orderBook.getTicker());
            return;
        }

        Order bestBid = orderBook.getBids(0); // лучшая цена покупки
        Order bestAsk = orderBook.getAsks(0); // лучшая цена продажи

        BigDecimal bestBidPrice = quotationToBigDecimal(bestBid.getPrice());
        BigDecimal bestAskPrice = quotationToBigDecimal(bestAsk.getPrice());

        long bestBidVolume = bestBid.getQuantity();
        long bestAskVolume = bestAsk.getQuantity();

        switch (orderBook.getInstrumentUid()) {
            case SHARE_UID -> {
                shareBid.set(bestBidPrice);
                shareAsk.set(bestAskPrice);
                shareBidQty.set(bestBidVolume);
                shareAskQty.set(bestAskVolume);
            }
            case FUTURE_UID -> {
                futureBid.set(bestBidPrice);
                futureAsk.set(bestAskPrice);
                futureBidQty.set(bestBidVolume);
                futureAskQty.set(bestAskVolume);
            }
        }
        updateSpreads();
    }

    private void updateSpreads() {
        if (shareBidQty.get() * shareAskQty.get() * futureBidQty.get() * futureAskQty.get() == 0) {
            log.info("Не хватает данных для расчёта цены раздвижки");
            return;
        }
        BigDecimal spreadSell = futureBid.get().subtract(shareAsk.get());
        BigDecimal spreadBuy = futureAsk.get().subtract(shareBid.get());
        long spreadSellQty = Math.min(futureBidQty.get(), shareAskQty.get());
        long spreadBuyQty = Math.min(futureAskQty.get(), shareBidQty.get());
        System.out.printf("Spread Sell: %s,\tSpread Sell qty: %s,\tSpread Buy: %s,\tSpread Buy qty: %s\n",
                spreadSell, spreadSellQty, spreadBuy, spreadBuyQty);
    }

//    private void printBestBidAndAsk(OrderBook instrument) {
//        if (instrument.getBidsCount() == 0 || instrument.getAsksCount() == 0) {
//            log.warn("Стакан для {} пуст", SHARE_UID);
//            return;
//        }
//
//        Order bestBid = instrument.getBids(0); // лучшая цена покупки
//        Order bestAsk = instrument.getAsks(0); // лучшая цена продажи
//
//        double bestBidPrice = quotationToBigDecimal(bestBid.getPrice());
//        double bestAskPrice = quotationToBigDecimal(bestAsk.getPrice());
//
//        long bestBidVolume = bestBid.getQuantity();
//        long bestAskVolume = bestAsk.getQuantity();
//
//        System.out.printf(
//                "[Обновление стакана] T | Лучшая покупка: %.4f (%d) | Лучшая продажа: %.4f (%d)%n",
//                bestBidPrice, bestBidVolume,
//                bestAskPrice, bestAskVolume
//        );
//    }

    private double quotationToDouble(Quotation q) {
        return q.getUnits() + q.getNano() / 1_000_000_000.0;
    }

    private BigDecimal quotationToBigDecimal(Quotation q) {
        BigDecimal units = BigDecimal.valueOf(q.getUnits());
        BigDecimal nano = BigDecimal.valueOf(q.getNano(), 9);
        BigDecimal result = units.add(nano);
        return result.setScale(2, RoundingMode.HALF_UP);
    }
}