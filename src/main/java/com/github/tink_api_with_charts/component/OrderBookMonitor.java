package com.github.tink_api_with_charts.component;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.tinkoff.piapi.contract.v1.InstrumentsServiceGrpc;
import ru.tinkoff.piapi.contract.v1.MarketDataStreamServiceGrpc;
import ru.tinkoff.piapi.contract.v1.Order;
import ru.tinkoff.piapi.contract.v1.OrderBook;
import ru.tinkoff.piapi.contract.v1.OrderBookType;
import ru.tinkoff.piapi.contract.v1.Quotation;
import ru.ttech.piapi.core.connector.ServiceStubFactory;
import ru.ttech.piapi.core.connector.streaming.StreamManagerFactory;
import ru.ttech.piapi.core.connector.streaming.StreamServiceStubFactory;
import ru.ttech.piapi.core.impl.marketdata.MarketDataStreamManager;
import ru.ttech.piapi.core.impl.marketdata.subscription.Instrument;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@Component
public class OrderBookMonitor {

    private final MarketDataStreamManager marketDataStreamManager;

    // Идентификатор инструмента (figi или ticker) T,TCS80A107UL4,87db07bc-0e02-4e29-90bb-05e8ef791d7b
    private static final String INSTRUMENT_UID = "87db07bc-0e02-4e29-90bb-05e8ef791d7b";

    public OrderBookMonitor(
            MarketDataStreamManager marketDataStreamManager
//            StreamManagerFactory streamManagerFactory
//            ServiceStubFactory serviceStubFactory
    ) {
//        var streamServiceFactory = StreamServiceStubFactory.create(serviceStubFactory);
//        var streamManagerFactory = StreamManagerFactory.create(streamServiceFactory);
//        ExecutorService executorService = Executors.newSingleThreadExecutor();
//        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
//        this.marketDataStreamManager = streamManagerFactory.newMarketDataStreamManager(executorService, scheduledExecutorService);
        this.marketDataStreamManager = marketDataStreamManager;
    }

    @PostConstruct
    public void startMonitoring() {
        log.info("Запуск мониторинга стакана для INSTRUMENT_UID={}", INSTRUMENT_UID);

        marketDataStreamManager.subscribeOrderBooks(
                Set.of(new Instrument(INSTRUMENT_UID, 10, OrderBookType.ORDERBOOK_TYPE_ALL)),
                orderBookWrapper -> {
//                    log.info("{}", orderBookWrapper.toString());
//                    if (orderBookWrapper.getInstrumentUid().equals(INSTRUMENT_UID)) {
                        printBestBidAndAsk(orderBookWrapper.getOriginal());
//                    }
                });
        marketDataStreamManager.start();
    }

    private void printBestBidAndAsk(OrderBook instrument) {
        if (instrument.getBidsCount() == 0 || instrument.getAsksCount() == 0) {
            log.warn("Стакан для {} пуст", INSTRUMENT_UID);
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