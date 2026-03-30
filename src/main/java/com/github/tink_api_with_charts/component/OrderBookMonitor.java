package com.github.tink_api_with_charts.component;

import com.google.common.util.concurrent.AtomicDouble;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.tinkoff.piapi.contract.v1.Order;
import ru.tinkoff.piapi.contract.v1.OrderBook;
import ru.tinkoff.piapi.contract.v1.OrderBookType;
import ru.tinkoff.piapi.contract.v1.Quotation;
import ru.ttech.piapi.core.impl.marketdata.MarketDataStreamManager;
import ru.ttech.piapi.core.impl.marketdata.subscription.Instrument;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class OrderBookMonitor {

    private final MarketDataStreamManager marketDataStreamManager;

    // Идентификатор инструмента (figi или ticker)
    // T,TCS80A107UL4,87db07bc-0e02-4e29-90bb-05e8ef791d7b
    // T,	 TBM6,	 FUTT06260000,	 12275713-0583-4add-a4a5-c7dd234d4f34
    private static final String SHARE_UID = "87db07bc-0e02-4e29-90bb-05e8ef791d7b";
    private static final String FUTURE_UID = "12275713-0583-4add-a4a5-c7dd234d4f34";

    private static final AtomicDouble shareBid = new AtomicDouble();
    private static final AtomicDouble shareAsk = new AtomicDouble();
    private static final AtomicDouble futureBid = new AtomicDouble();
    private static final AtomicDouble futureAsk = new AtomicDouble();

    private static final AtomicLong shareBidQty = new AtomicLong();
    private static final AtomicLong shareAskQty = new AtomicLong();
    private static final AtomicLong futureBidQty = new AtomicLong();
    private static final AtomicLong futureAskQty = new AtomicLong();

    public OrderBookMonitor(
            MarketDataStreamManager marketDataStreamManager
    ) {
        this.marketDataStreamManager = marketDataStreamManager;
    }

        @PostConstruct
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

        double bestBidPrice = quotationToDouble(bestBid.getPrice());
        double bestAskPrice = quotationToDouble(bestAsk.getPrice());

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
        double spreadSell = futureBid.get() - shareAsk.get();
        double spreadBuy = futureAsk.get() - shareBid.get();
        System.out.printf("Spread Sell: %s,\tSpread Buy: %s\n", spreadSell, spreadBuy);
    }

    private void updateShareOrderBook(OrderBook orderBook) {

    }

    private void printBestBidAndAsk(OrderBook instrument) {
        if (instrument.getBidsCount() == 0 || instrument.getAsksCount() == 0) {
            log.warn("Стакан для {} пуст", SHARE_UID);
            return;
        }

        Order bestBid = instrument.getBids(0); // лучшая цена покупки
        Order bestAsk = instrument.getAsks(0); // лучшая цена продажи

        double bestBidPrice = quotationToDouble(bestBid.getPrice());
        double bestAskPrice = quotationToDouble(bestAsk.getPrice());

        long bestBidVolume = bestBid.getQuantity();
        long bestAskVolume = bestAsk.getQuantity();

        System.out.printf(
                "[Обновление стакана] T | Лучшая покупка: %.4f (%d) | Лучшая продажа: %.4f (%d)%n",
                bestBidPrice, bestBidVolume,
                bestAskPrice, bestAskVolume
        );
    }

    private double quotationToDouble(Quotation q) {
        return q.getUnits() + q.getNano() / 1_000_000_000.0;
    }
}