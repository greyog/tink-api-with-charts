package com.github.tink_api_with_charts.entity;

import lombok.Data;
import ru.tinkoff.piapi.contract.v1.OrderDirection;
import ru.tinkoff.piapi.contract.v1.OrderExecutionReportStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Состояние заявки на исполнение
 */
@Data
public class OrderExecutionState {
    
    private final String requestId;              // orderRequestId
    private final String instrumentUid;        // идентификатор инструмента
    private final OrderDirection direction;    // направление
    private final long quantity;               // запрошенное количество
    private final BigDecimal price;            // цена
    private final Instant createdAt;           // время создания
    
    private OrderExecutionReportStatus status; // текущий статус (из SDK)
    private Instant updatedAt;                 // время обновления статуса
    
    private long executedQuantity;             // исполненное количество
    private BigDecimal executedAmount;         // исполненная сумма
    
    private boolean pendingConfirmation;       // ожидает подтверждения после разрыва/отправки
    private int retryCount;                    // попыток восстановления
    private Instant lastSyncAttempt;           // последняя попытка синхронизации
    private boolean marketOrder;               // флаг market заявки (с блокировкой инструмента)
    private boolean waitingForPositionInfo;    // флаг ожидания обновления информации по позициям
    private String tradeIntentId;              // trade_intent_id биржевой айди заявки

    public OrderExecutionState(String requestId, String instrumentUid, OrderDirection direction,
                               long quantity, BigDecimal price) {
        this.requestId = requestId;
        this.instrumentUid = instrumentUid;
        this.direction = direction;
        this.quantity = quantity;
        this.price = price;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.status = OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_NEW;
        this.executedQuantity = 0;
        this.retryCount = 0;
    }
    
    /**
     * Проверка на терминальный статус (заявка завершена)
     */
    public boolean isTerminalStatus() {
        return switch (this.status) {
            case EXECUTION_REPORT_STATUS_FILL,
                 EXECUTION_REPORT_STATUS_CANCELLED,
                 EXECUTION_REPORT_STATUS_REJECTED -> true;
            default -> false;
        };
    }
    
    /**
     * Проверка на статус ожидания (заявка отправлена, но не подтверждена)
     */
    public boolean isPendingStatus() {
        return this.pendingConfirmation;
    }
    
    /**
     * Проверка на устаревание (давно не обновлялось)
     */
    public boolean isStale(Duration threshold) {
        if (lastSyncAttempt == null) {
            return updatedAt.isBefore(Instant.now().minus(threshold));
        }
        return lastSyncAttempt.isBefore(Instant.now().minus(threshold));
    }
    
    /**
     * Инкремент счётчика попыток
     */
    public void incrementRetryCount() {
        this.retryCount++;
    }
}
