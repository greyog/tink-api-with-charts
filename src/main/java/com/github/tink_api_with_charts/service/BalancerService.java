package com.github.tink_api_with_charts.service;

import com.github.tink_api_with_charts.cinfiguration.BalancerProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class BalancerService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BalancerService.class);
    private static final BigDecimal THROW_FOR_MARKET_ORDER = BigDecimal.valueOf(0.01);
    private static final BigDecimal BUY_ORDER_OFFSET =  BigDecimal.ONE.add(THROW_FOR_MARKET_ORDER);
    private static final BigDecimal SELL_ORDER_OFFSET =  BigDecimal.ONE.subtract(THROW_FOR_MARKET_ORDER);
    private final BalancerProperties properties;
    private final TradeExecutionService tradeExecutionService;
    private final double upperAlloc;
    private final double lowerAlloc;
    private final double targetAlloc;
    private final double deltaUp;
    private final double deltaDown;

    public BalancerService(BalancerProperties properties, TradeExecutionService tradeExecutionService) {
        this.properties = properties;
        this.tradeExecutionService = tradeExecutionService;
        deltaUp = properties.getRebalanceThresholdUp();
        deltaDown = properties.getRebalanceThresholdDown();
        targetAlloc = properties.getTargetShareAllocation();
        upperAlloc = targetAlloc + deltaUp;
        lowerAlloc = targetAlloc - deltaDown;
    }

    public void handleStateChange(String trigger, BigDecimal cashValue,
                                  long shareQty, BigDecimal shareBidPrice,
                                  long cashEtfQty, BigDecimal cashEtfBidPrice) {
//        log.info("BalancerService.handleStateChange. Trigger: {}, \tCashValue: {}, \tShare qty: {}, \tshare bid: {}, \tcash ETF qty: {}, \tcash ETF bid: {}",
//                trigger, cashValue, shareQty, shareBidPrice, cashEtfQty, cashEtfBidPrice);
        double shareValue = shareBidPrice.doubleValue() * shareQty;
        double totalCashValue = cashEtfBidPrice.doubleValue() * (cashEtfQty + properties.getIisCashEtfQty()) + cashValue.doubleValue();
        double totalValue = shareValue + totalCashValue;
        double shareAllocation = shareValue / totalValue;

        double allocDelta = shareAllocation - targetAlloc;
        double needChangeDelta = - allocDelta;
        double cashNeedChange = totalCashValue * needChangeDelta;
        long shareNeedChange = Math.round(cashNeedChange / shareBidPrice.doubleValue());
        if (shareAllocation > upperAlloc) {
            log.info("Target alloc: {}, current share alloc: {}. Need to Sell: {} shares",
                    String.format("%.6f", targetAlloc),
                    String.format("%.6f", shareAllocation),
                    shareNeedChange
            );
            tradeExecutionService.marketSell(properties.getShareUid(), shareBidPrice.multiply(SELL_ORDER_OFFSET), Math.abs(shareNeedChange));
        } else if (shareAllocation < lowerAlloc) {
            log.info("Target alloc: {}, current share alloc: {}. Need to Buy: {} shares",
                    String.format("%.6f", targetAlloc),
                    String.format("%.6f", shareAllocation),
                    shareNeedChange
            );
            tradeExecutionService.marketBuy(properties.getShareUid(), shareBidPrice.multiply(BUY_ORDER_OFFSET), Math.abs(shareNeedChange));
        } else {
            double sharePriceAtUpperAlloc = totalCashValue / shareQty * upperAlloc / (1 - upperAlloc);
            long qtyToSellAtUpperAlloc = Math.round(shareQty * deltaUp / upperAlloc);

            double sharePriceAtLowerAlloc = totalCashValue / shareQty * lowerAlloc / (1 - lowerAlloc);
            long qtyToBuyAtLowerAlloc = Math.round(shareQty * deltaDown / lowerAlloc);
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
}
