package com.github.tink_api_with_charts.service;

import com.github.tink_api_with_charts.cinfiguration.BalancerProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class BalancerStateService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BalancerStateService.class);

    private final BalancerProperties properties;
    private final BalancerService balancerService;

    private final ConcurrentHashMap<String, BidAsk> prices = new ConcurrentHashMap<>();
    private final AtomicReference<BigDecimal> cashValue = new AtomicReference<>(null);
    private final AtomicReference<BigDecimal> shareQty = new AtomicReference<>(null);

    public BalancerStateService(BalancerProperties properties, BalancerService balancerService) {
        this.properties = properties;
        this.balancerService = balancerService;
    }

    public void updatePriceData(String instrumentUid, BigDecimal bid, BigDecimal ask) {
        prices.put(instrumentUid, new BidAsk(bid, ask));
        notifyBalancerService();
    }

    public void updateCashValue(BigDecimal newValue) {
        cashValue.set(newValue);
        notifyBalancerService();
    }

    private void notifyBalancerService() {
        if (stateIsOk()) {
            balancerService.handleStateChange(cashValue.get(),
                    0L,
                    prices.get(properties.getShareUid()).bid,
                    0L,
                    prices.get(properties.getCashEtfUid()).bid);
        }
    }

    private boolean stateIsOk() {
        boolean pricesOk = prices.size() == 2;
        boolean cashOk = cashValue.get() != null;
        boolean shareQtyOk = shareQty.get() != null;
        return pricesOk && cashOk && shareQtyOk;
    }

    public void updateShareQty(long newShareQty) {
        shareQty.set(BigDecimal.valueOf(newShareQty));
    }

    private record BidAsk(BigDecimal bid, BigDecimal ask) {}
}
