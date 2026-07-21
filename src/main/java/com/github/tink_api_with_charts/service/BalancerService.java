package com.github.tink_api_with_charts.service;

import com.github.tink_api_with_charts.cinfiguration.BalancerProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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

    private final AtomicReference<BigDecimal> lastSharePriceAtUpperAlloc = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> lastSharePriceAtLowerAlloc = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicLong lastQtyToSellAtUpperAlloc = new AtomicLong(0);
    private final AtomicLong lastQtyToBuyAtLowerAlloc = new AtomicLong(0);

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

        // shareChange = (+-1) * (targetAlloc * (shareQty + totalCashValue / sharePrice) - shareQty))
        long targetShareQty = Math.round(targetAlloc * (shareQty + totalCashValue / shareBidPrice.doubleValue()));
        if (shareAllocation > upperAlloc) {
            long shareChange = shareQty - targetShareQty;
            log.info("Price {}, \t, Share Qty {}, \tTarget alloc: {}, \tcurrent share alloc: {}. \tNeed to Sell: {} shares",
                    shareBidPrice,
                    shareQty,
                    String.format("%.6f", targetAlloc),
                    String.format("%.6f", shareAllocation),
                    shareChange
            );
            tradeExecutionService.marketSell(properties.getShareUid(), shareBidPrice.multiply(SELL_ORDER_OFFSET), shareChange);
        } else if (shareAllocation < lowerAlloc) {
            long shareChange = targetShareQty - shareQty;
            log.info("Price {}, \t, Share Qty {}, \tTarget alloc: {}, \tcurrent share alloc: {}. \tNeed to Buy: {} shares",
                    shareBidPrice,
                    shareQty,
                    String.format("%.6f", targetAlloc),
                    String.format("%.6f", shareAllocation),
                    shareChange
            );
            tradeExecutionService.marketBuy(properties.getShareUid(), shareBidPrice.multiply(BUY_ORDER_OFFSET), shareChange);
        } else {
            double sharePriceAtUpperAlloc = totalCashValue / shareQty * upperAlloc / (1 - upperAlloc);
            long qtyToSellAtUpperAlloc = Math.round(shareQty * deltaUp / upperAlloc);

            double sharePriceAtLowerAlloc = totalCashValue / shareQty * lowerAlloc / (1 - lowerAlloc);
            long qtyToBuyAtLowerAlloc = Math.round(shareQty * deltaDown / lowerAlloc);

            int updateCount = 0;
            BigDecimal nextSharePriceAtUpperAlloc = BigDecimal.valueOf(sharePriceAtUpperAlloc).setScale(2, RoundingMode.HALF_UP);
            if (nextSharePriceAtUpperAlloc.compareTo(lastSharePriceAtUpperAlloc.get()) != 0) {
                lastSharePriceAtUpperAlloc.set(nextSharePriceAtUpperAlloc);
                updateCount++;
            }
            BigDecimal nextSharePriceAtLowerAlloc = BigDecimal.valueOf(sharePriceAtLowerAlloc).setScale(2, RoundingMode.HALF_UP);
            if (nextSharePriceAtLowerAlloc.compareTo(lastSharePriceAtLowerAlloc.get()) != 0) {
                lastSharePriceAtLowerAlloc.set(nextSharePriceAtLowerAlloc);
                updateCount++;
            }
            if (qtyToSellAtUpperAlloc != lastQtyToSellAtUpperAlloc.get()) {
                lastQtyToSellAtUpperAlloc.set(qtyToSellAtUpperAlloc);
                updateCount++;
            }
            if (qtyToBuyAtLowerAlloc != lastQtyToBuyAtLowerAlloc.get()) {
                lastQtyToBuyAtLowerAlloc.set(qtyToBuyAtLowerAlloc);
                updateCount++;
            }
            if (updateCount > 0) {
                log.info("Price {}, \t, Share Qty {}, \tPortfolio Value {}, \tCurrent alloc {}, \tsharePriceAtUpperAlloc {}, \tqtyToSellAtUpperAlloc {}, \tsharePriceAtLowerAlloc {}, \tqtyToBuyAtLowerAlloc {}",
                        shareBidPrice,
                        shareQty,
                        String.format("%.2f", totalValue),
                        String.format("%.6f", shareAllocation),
                        String.format("%.2f", sharePriceAtUpperAlloc),
                        qtyToSellAtUpperAlloc,
                        String.format("%.2f", sharePriceAtLowerAlloc),
                        qtyToBuyAtLowerAlloc);
            }
        }
    }
}
