package com.github.tink_api_with_charts.event;

import lombok.Data;
import org.springframework.context.ApplicationEvent;
import ru.tinkoff.piapi.contract.v1.OrderDirection;

import java.math.BigDecimal;

@Data
public class TradeCompletedEvent extends ApplicationEvent {
    private final String instrumentUid;
    private final OrderDirection direction;
    private final BigDecimal amount;

    public TradeCompletedEvent(Object source, String instrumentUid, OrderDirection direction, BigDecimal amount) {
        super(source);
        this.instrumentUid = instrumentUid;
        this.direction = direction;
        this.amount = amount;
    }

}