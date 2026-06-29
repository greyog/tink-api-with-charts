package com.github.tink_api_with_charts.service;

import com.github.tink_api_with_charts.cinfiguration.BalancerProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class BalancerService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BalancerService.class);
    private final BalancerProperties properties;

    public BalancerService(BalancerProperties properties) {
        this.properties = properties;
    }

    public void handleStateChange(String trigger, BigDecimal cashValue,
                                  long shareQty, BigDecimal shareBidPrice,
                                  long cashEtfQty, BigDecimal cashEtfBidPrice) {
        log.info("BalancerService.handleStateChange. Trigger: {}, CashValue: {}, Share qty: {}, share bid: {}, cash ETF qty: {}, cash ETF bid: {}",
                trigger, cashValue, shareQty, shareBidPrice, cashEtfQty, cashEtfBidPrice);
    }
}
