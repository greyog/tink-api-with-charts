package com.github.tink_api_with_charts.service;

import com.github.tink_api_with_charts.cinfiguration.BalancerProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class BalancerService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BalancerService.class);
    private static final long iisCashEtfQty = 2501;
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
        double totalCashValue = cashEtfBidPrice.doubleValue() * (cashEtfQty + iisCashEtfQty) + cashValue.doubleValue();
        double totalValue = shareValue + totalCashValue;
        double shareAllocation = shareValue / totalValue;

        double newShareValue = totalCashValue * properties.getTargetShareAllocation() / (1 - properties.getTargetShareAllocation());
        long newShareQty = (long) Math.floor(newShareValue / shareBidPrice.doubleValue());
        if (shareAllocation > upperAlloc) {
            long shareQtyToSell =  shareQty - newShareQty;
            // todo should sell
            log.warn("Need to sell {}", shareQtyToSell);
        } else {
            if (shareAllocation < lowerAlloc) {
                long shareQtyToBuy =  newShareQty - shareQty;
                // todo should buy
                log.warn("Need to buy {}", shareQtyToBuy);
            }
        }
        double shareValueAtUpperAlloc = totalCashValue * upperAlloc / (1 - upperAlloc);
        double sharePriceAtUpperAlloc = shareValueAtUpperAlloc / shareQty;
        long newShareQtyAtUpperAlloc = (long) Math.floor(newShareValue / sharePriceAtUpperAlloc);
        long qtyToSellAtUpperAlloc = shareQty - newShareQtyAtUpperAlloc;

        double shareValueAtlowerAlloc = totalCashValue * lowerAlloc / (1 - lowerAlloc);
        double sharePriceAtLowerAlloc = shareValueAtlowerAlloc / shareQty;
        long newShareQtyAtLowerAlloc = (long) Math.floor(newShareValue / sharePriceAtLowerAlloc);
        long qtyToBuyAtLowerAlloc = newShareQtyAtLowerAlloc - shareQty;
        log.info("Price {}, \tPortfolio Value {}, \tCurrent alloc {}, \tsharePriceAtUpperAlloc {}, \tqtyToSellAtUpperAlloc {}, \tsharePriceAtLowerAlloc {}, \tqtyToBuyAtLowerAlloc {}",
                shareBidPrice,
                String.format("%.2f", totalValue),
                String.format("%.6f", shareAllocation),
                String.format("%.2f", sharePriceAtUpperAlloc),
                qtyToSellAtUpperAlloc,
                String.format("%.2f", sharePriceAtLowerAlloc),
                qtyToBuyAtLowerAlloc);
    }
}
