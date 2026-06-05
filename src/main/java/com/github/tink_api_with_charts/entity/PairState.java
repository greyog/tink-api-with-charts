package com.github.tink_api_with_charts.entity;

import ru.tinkoff.piapi.contract.v1.InstrumentType;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Состояние одной пары инструментов
 */
public class PairState {

    // Цены и объёмы по акции
    private final AtomicReference<BigDecimal> shareBid = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> shareAsk = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicLong shareBidQty = new AtomicLong();
    private final AtomicLong shareAskQty = new AtomicLong();

    // Цены и объёмы по фьючерсу
    private final AtomicReference<BigDecimal> futureBid = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> futureAsk = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicLong futureBidQty = new AtomicLong();
    private final AtomicLong futureAskQty = new AtomicLong();

    // Флаги наличия данных
    private volatile boolean hasShareData = false;
    private volatile boolean hasFutureData = false;

    /**
     * Обновление состояния по данным стакана
     */
    public synchronized void update(InstrumentType instrumentType, BigDecimal bidPrice, BigDecimal askPrice,
                                    long bidQty, long askQty) {
        if (instrumentType.equals(InstrumentType.INSTRUMENT_TYPE_FUTURES)) {
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
    public boolean hasBothInstruments() {
        return hasShareData && hasFutureData;
    }

    public BigDecimal getShareBid() {
        return shareBid.get();
    }

    public BigDecimal getShareAsk() {
        return shareAsk.get();
    }

    public Long getShareBidQty() {
        return shareBidQty.get();
    }

    public Long getShareAskQty() {
        return shareAskQty.get();
    }

    public BigDecimal getFutureBid() {
        return futureBid.get();
    }

    public BigDecimal getFutureAsk() {
        return futureAsk.get();
    }

    public Long getFutureBidQty() {
        return futureBidQty.get();
    }

    public Long getFutureAskQty() {
        return futureAskQty.get();
    }

}
