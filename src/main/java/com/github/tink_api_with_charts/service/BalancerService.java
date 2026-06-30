package com.github.tink_api_with_charts.service;

import com.github.tink_api_with_charts.cinfiguration.BalancerProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class BalancerService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BalancerService.class);
    private final BalancerProperties properties;
    private final double upperAlloc;
    private final double lowerAlloc;

    public BalancerService(BalancerProperties properties) {
        this.properties = properties;
        upperAlloc = properties.getTargetShareAllocation() + properties.getRebalanceThreshold();
        lowerAlloc = properties.getTargetShareAllocation() - properties.getRebalanceThreshold();
    }

    public void handleStateChange(String trigger, BigDecimal cashValue,
                                  long shareQty, BigDecimal shareBidPrice,
                                  long cashEtfQty, BigDecimal cashEtfBidPrice) {
//        log.info("BalancerService.handleStateChange. Trigger: {}, \tCashValue: {}, \tShare qty: {}, \tshare bid: {}, \tcash ETF qty: {}, \tcash ETF bid: {}",
//                trigger, cashValue, shareQty, shareBidPrice, cashEtfQty, cashEtfBidPrice);
        double shareValue = shareBidPrice.doubleValue() * shareQty;
        double totalCashValue = cashEtfBidPrice.doubleValue() * cashEtfQty + cashValue.doubleValue();
        double totalValue = shareValue + totalCashValue;
        double shareAllocation = shareValue / totalValue;

        if (shareAllocation > upperAlloc) {
            double newShareValue = totalValue * properties.getTargetShareAllocation();
            long newShareQty = (long) Math.floor(newShareValue / shareBidPrice.doubleValue());
            double shareQtyToSell =  shareQty - newShareQty;
            // todo should sell
        } else {
            if (shareAllocation < lowerAlloc) {
                double newShareValue = totalValue * properties.getTargetShareAllocation();
                long newShareQty = (long) Math.floor(newShareValue / shareBidPrice.doubleValue());
                double shareQtyToBuy =  newShareQty - shareQty;
                // todo should buy
            }
        }
    }
}
