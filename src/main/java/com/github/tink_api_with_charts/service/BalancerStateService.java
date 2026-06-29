package com.github.tink_api_with_charts.service;

import com.github.tink_api_with_charts.cinfiguration.BalancerProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;
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

    public void updateSharePrice(BigDecimal bid, BigDecimal ask) {
        String triggerType = "share";
        updateIfNeeded(bid, ask, shareBid, shareAsk, triggerType);
    }

    public void updateCashEtfPrice(BigDecimal bid, BigDecimal ask) {
        String triggerType = "cash ETF";
        updateIfNeeded(bid, ask, cashEtfBid, cashEtfAsk, triggerType);
    }

    private void updateIfNeeded(BigDecimal bid, BigDecimal ask, AtomicReference<BigDecimal> oldBidRef, AtomicReference<BigDecimal> oldAskRef, String triggerType) {
        BigDecimal oldBid = oldBidRef.get();
        BigDecimal oldAsk = oldAskRef.get();
        boolean equalsBid = Objects.equals(oldBid, bid);
        boolean equalsAsk = Objects.equals(oldAsk, ask);
        if (equalsAsk && equalsBid) {
            return;
        }
        if (!equalsBid && equalsAsk) {
            oldBidRef.set(bid);
            notifyBalancerService("update %s bid".formatted(triggerType));
        } else if (equalsBid && !equalsAsk) {
            oldAskRef.set(ask);
            notifyBalancerService("update %s ask".formatted(triggerType));
        } else {
            oldBidRef.set(bid);
            oldAskRef.set(ask);
            notifyBalancerService("update %s bid ask".formatted(triggerType));
        }
    }

    public void updateCashValue(BigDecimal newValue) {
        BigDecimal oldValue = cashValue.get();
        if (!Objects.equals(oldValue, newValue)) {
            cashValue.set(newValue);
            notifyBalancerService("updateCashValue");
        }
    }

    public void updateShareQty(long newQty) {
        Long oldValue = shareQty.get();
        if (!Objects.equals(oldValue, newQty)) {
            shareQty.set(newQty);
            notifyBalancerService("updateShareQty");
        }
    }

    public void updateCashEtfQty(long newQty) {
        Long oldValue = cashEtfQty.get();
        if (!Objects.equals(oldValue, newQty)) {
            cashEtfQty.set(newQty);
            notifyBalancerService("updateCashEtfQty");
        }
    }

    private void notifyBalancerService(String trigger) {
        if (stateIsOk()) {
            balancerService.handleStateChange(trigger, cashValue.get(),
                    shareQty.get(),
                    shareBid.get(),
                    cashEtfQty.get(),
                    cashEtfBid.get());
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
