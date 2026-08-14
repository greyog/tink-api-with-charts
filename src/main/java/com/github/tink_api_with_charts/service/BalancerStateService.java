package com.github.tink_api_with_charts.service;

import com.github.tink_api_with_charts.cinfiguration.BalancerProperties;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class BalancerStateService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BalancerStateService.class);

    private final BalancerProperties properties;
    private final BalancerService balancerService;

    private final AtomicReference<BigDecimal> shareBid = new AtomicReference<>(null);
    private final AtomicReference<BigDecimal> shareAsk = new AtomicReference<>(null);
    private final AtomicReference<BigDecimal> cashEtfBid = new AtomicReference<>(null);
    private final AtomicReference<BigDecimal> cashEtfAsk = new AtomicReference<>(null);
    private final AtomicReference<BigDecimal> cashValue = new AtomicReference<>(null);
    private final AtomicReference<Long> shareQty = new AtomicReference<>(null);
    private final AtomicReference<Long> cashEtfQty = new AtomicReference<>(null);

    public BalancerStateService(BalancerProperties properties, BalancerService balancerService) {
        this.properties = properties;
        this.balancerService = balancerService;
    }

    @Async
    public void updateSharePrice(BigDecimal bid, BigDecimal ask) {
        String triggerType = "share";
        updatePricesIfNeeded(bid, ask, shareBid, shareAsk, triggerType);
    }

    @Async
    public void updateCashEtfPrice(BigDecimal bid, BigDecimal ask) {
        String triggerType = "cash ETF";
        updatePricesIfNeeded(bid, ask, cashEtfBid, cashEtfAsk, triggerType);
    }

    private void updatePricesIfNeeded(BigDecimal bid, BigDecimal ask, AtomicReference<BigDecimal> oldBidRef, AtomicReference<BigDecimal> oldAskRef, String triggerType) {
        BigDecimal oldBid = oldBidRef.get();
        BigDecimal oldAsk = oldAskRef.get();
        boolean equalsBid = Objects.equals(oldBid, bid);
        boolean equalsAsk = Objects.equals(oldAsk, ask);
        if (equalsAsk && equalsBid) {
            return;
        }
        if (!equalsBid && equalsAsk) {
            oldBidRef.set(bid);
            notifyBalancerService("%s bid".formatted(triggerType), false);
        } else if (equalsBid && !equalsAsk) {
            oldAskRef.set(ask);
            notifyBalancerService("%s ask".formatted(triggerType), false);
        } else {
            oldBidRef.set(bid);
            oldAskRef.set(ask);
            notifyBalancerService("%s bid ask".formatted(triggerType), false);
        }
    }

    @Async
    public void updateFromPositionInfo(BigDecimal newCashValue, long newShareQty, long newCashEtfQty) {
        int updatesCount = 0;
        if (!Objects.equals(cashValue.get(), newCashValue)) {
            cashValue.set(newCashValue);
            updatesCount++;
        }
        if (!Objects.equals(shareQty.get(), newShareQty)) {
            shareQty.set(newShareQty);
            updatesCount++;
        }
        if (!Objects.equals(cashEtfQty.get(), newCashEtfQty)) {
            cashEtfQty.set(newCashEtfQty);
            updatesCount++;
        }
        if (updatesCount > 0) {
            notifyBalancerService("Position update", true);
        }
    }

    @Async
    public void updateFromPositionMonitor(Optional<BigDecimal> newCashValue, Optional<Long> newShareQty, Optional<Long> newCashEtfQty) {
        AtomicInteger updatesCount = new AtomicInteger();
        newCashValue
                .filter(newValue -> !Objects.equals(cashValue.get(), newValue) )
                .ifPresent(newValue -> {
                    cashValue.set(newValue);
                    updatesCount.getAndIncrement();
                });
        newShareQty
                .filter(newValue -> !Objects.equals(shareQty.get(), newValue) )
                .ifPresent(newValue -> {
                    shareQty.set(newValue);
                    updatesCount.getAndIncrement();
                });
        newCashEtfQty
                .filter(newValue -> !Objects.equals(cashEtfQty.get(), newValue) )
                .ifPresent(newValue -> {
                    cashEtfQty.set(newValue);
                    updatesCount.getAndIncrement();
                });
        if (updatesCount.get() > 0) {
            notifyBalancerService("Position update", true);
        }
    }

    private void notifyBalancerService(String trigger, boolean releaseLock) {
        if (stateIsOk()) {
            balancerService.handleStateChange(trigger, cashValue.get(),
                    shareQty.get(),
                    shareBid.get(),
                    cashEtfQty.get(),
                    cashEtfBid.get(),
                    releaseLock);
        }
    }

    private boolean stateIsOk() {
        boolean cashOk = cashValue.get() != null;
        boolean shareBidOk = shareBid.get() != null;
        boolean shareQtyOk = shareQty.get() != null;
        boolean cashBidOk = cashEtfBid.get() != null;
        boolean cashQtyOk = cashEtfQty.get() != null;
        return shareBidOk && cashOk && shareQtyOk && cashQtyOk && cashBidOk;
    }

    private record BidAsk(BigDecimal bid, BigDecimal ask) {}
}
