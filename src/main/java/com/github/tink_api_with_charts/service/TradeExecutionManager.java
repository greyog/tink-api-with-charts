package com.github.tink_api_with_charts.service;

import com.github.tink_api_with_charts.cinfiguration.BalancerProperties;
import com.github.tink_api_with_charts.entity.OrderExecutionState;
import com.github.tink_api_with_charts.event.PositionInfoUpdatedEvent;
import com.github.tink_api_with_charts.event.TradeCompletedEvent;
import com.github.tink_api_with_charts.utils.ConcurrentSlidingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import ru.tinkoff.piapi.contract.v1.OrderDirection;
import ru.tinkoff.piapi.contract.v1.OrderExecutionReportStatus;
import ru.tinkoff.piapi.contract.v1.OrderState;
import ru.tinkoff.piapi.contract.v1.OrderStateStreamResponse;
import ru.ttech.piapi.core.helpers.NumberMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Менеджер управления заявками на исполнение
 * Отвечает за отслеживание статуса заявок и восстановление после разрывов соединения
 */
@Service
public class TradeExecutionManager {

    private static final Logger log = LoggerFactory.getLogger(TradeExecutionManager.class);
    private static final Duration DEFAULT_STALE_THRESHOLD = Duration.ofMinutes(2);

    private final TradeExecutionService tradeExecutionService;
    private final ApplicationEventPublisher eventPublisher;
    private final BalancerProperties properties;
    private final ScheduledExecutorService recoveryExecutor;

    // Активные заявки
    private final ConcurrentMap<String, OrderExecutionState> activeOrders = new ConcurrentHashMap<>();

    // Завершённые заявки (для дедупликации и истории)
    private final ConcurrentSlidingCache<String> finishedOrders = new ConcurrentSlidingCache<>();

    // Флаг состояния соединения
    private final AtomicBoolean streamConnected = new AtomicBoolean(false);

    // Блокировка новых заявок по инструменту (для market orders)
    private final ConcurrentMap<String, AtomicBoolean> instrumentLocks = new ConcurrentHashMap<>();

    // Глобальная блокировка отправки новых заявок (ожидание обновления информации по позициям)
    private final AtomicBoolean waitingForPositionInfo = new AtomicBoolean(false);

    public TradeExecutionManager(
            TradeExecutionService tradeExecutionService,
            ApplicationEventPublisher eventPublisher,
            BalancerProperties properties
    ) {
        this.tradeExecutionService = tradeExecutionService;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.recoveryExecutor = Executors.newSingleThreadScheduledExecutor();

        // Запуск периодической сверки
        startPeriodicReconciliation();

        // Регистрация менеджера в сервисе для получения callback'ов
        tradeExecutionService.setTradeExecutionManager(this);
    }

    @EventListener
    public void onPositionInfoUpdated(PositionInfoUpdatedEvent event) {
        waitingForPositionInfo.set(false);
    }

    // ========== Публичные методы для BalancerService ==========

    /**
     * Проверить, можно ли отправить новую заявку по инструменту
     * (для market orders - блокировка до подтверждения предыдущей)
     */
    public boolean canSubmitOrder(String instrumentUid) {
        // Проверка глобальной блокировки ожидания информации по позициям
        if (waitingForPositionInfo.get()) {
            log.warn("Cannot submit order: waiting for position info update");
            return false;
        }

        // Проверка блокировки по инструменту (для market orders)
        AtomicBoolean lock = instrumentLocks.get(instrumentUid);
        return lock == null || !lock.get();
    }

    /**
     * Отправить заявку на покупку
     * @return OrderExecutionState для отслеживания статуса
     */
    public OrderExecutionState submitBuyOrder(String instrumentUid, BigDecimal price, long quantity) {
        // Проверка блокировки
        if (!canSubmitOrder(instrumentUid)) {
            log.warn("Cannot submit buy order: waiting for position info or instrument {} is locked", instrumentUid);
            throw new IllegalStateException("Cannot submit order: waiting for position info or instrument " + instrumentUid + " is locked");
        }

        String orderId = UUID.randomUUID().toString();
        OrderExecutionState state = new OrderExecutionState(
                orderId,
                instrumentUid,
                OrderDirection.ORDER_DIRECTION_BUY,
                quantity,
                price
        );
        state.setStatus(OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_NEW);
        state.setPendingConfirmation(true);
        state.setWaitingForPositionInfo(true);

        activeOrders.put(orderId, state);
        scheduleStatusRecovery(orderId);

        // Установка глобальной блокировки
        waitingForPositionInfo.set(true);

        tradeExecutionService.postLimitOrderWithTracking(orderId, instrumentUid, price, quantity, OrderDirection.ORDER_DIRECTION_BUY);

        log.info("Created buy order {}: {} x {} @ {}", orderId, instrumentUid, quantity, price);
        return state;
    }

    /**
     * Отправить заявку на продажу
     * @return OrderExecutionState для отслеживания статуса
     */
    public OrderExecutionState submitSellOrder(String instrumentUid, BigDecimal price, long quantity) {
        // Проверка блокировки
        if (!canSubmitOrder(instrumentUid)) {
            log.warn("Cannot submit sell order: waiting for position info or instrument {} is locked", instrumentUid);
            throw new IllegalStateException("Cannot submit order: waiting for position info or instrument " + instrumentUid + " is locked");
        }

        String orderId = UUID.randomUUID().toString();
        OrderExecutionState state = new OrderExecutionState(
                orderId,
                instrumentUid,
                OrderDirection.ORDER_DIRECTION_SELL,
                quantity,
                price
        );
        state.setStatus(OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_NEW);
        state.setPendingConfirmation(true);
        state.setWaitingForPositionInfo(true);

        activeOrders.put(orderId, state);
        scheduleStatusRecovery(orderId);

        // Установка глобальной блокировки
        waitingForPositionInfo.set(true);

        tradeExecutionService.postLimitOrderWithTracking(orderId, instrumentUid, price, quantity, OrderDirection.ORDER_DIRECTION_SELL);

        log.info("Created sell order {}: {} x {} @ {}", orderId, instrumentUid, quantity, price);
        return state;
    }

    /**
     * Отправить market заявку на покупку (с блокировкой инструмента)
     * @return OrderExecutionState для отслеживания статуса
     */
    public OrderExecutionState submitMarketBuyOrder(String instrumentUid, long quantity) {
        // Проверка блокировки
        if (!canSubmitOrder(instrumentUid)) {
            log.warn("Cannot submit market buy order: waiting for position info or instrument {} is locked", instrumentUid);
            throw new IllegalStateException("Cannot submit order: waiting for position info or instrument " + instrumentUid + " is locked");
        }

        // Установка блокировки по инструменту
        instrumentLocks.computeIfAbsent(instrumentUid, k -> new AtomicBoolean(false));
        AtomicBoolean instrumentLock = instrumentLocks.get(instrumentUid);
        if (!instrumentLock.compareAndSet(false, true)) {
            throw new IllegalStateException("Instrument " + instrumentUid + " is locked due to pending order");
        }

        String orderId = UUID.randomUUID().toString();
        OrderExecutionState state = new OrderExecutionState(
                orderId,
                instrumentUid,
                OrderDirection.ORDER_DIRECTION_BUY,
                quantity,
                BigDecimal.ZERO // Цена будет определена при исполнении
        );
        state.setStatus(OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_NEW);
        state.setPendingConfirmation(true);
        state.setMarketOrder(true);
        state.setWaitingForPositionInfo(true);

        activeOrders.put(orderId, state);
        scheduleStatusRecovery(orderId);

        // Установка глобальной блокировки
        waitingForPositionInfo.set(true);

        tradeExecutionService.postMarketOrderWithTracking(orderId, instrumentUid, quantity, OrderDirection.ORDER_DIRECTION_BUY);

        log.info("Created market buy order {}: {} x {}", orderId, instrumentUid, quantity);
        return state;
    }

    /**
     * Отправить market заявку на продажу (с блокировкой инструмента)
     * @return OrderExecutionState для отслеживания статуса
     */
    public OrderExecutionState submitMarketSellOrder(String instrumentUid, long quantity) {
        // Проверка блокировки
        if (!canSubmitOrder(instrumentUid)) {
            log.warn("Cannot submit market sell order: waiting for position info or instrument {} is locked", instrumentUid);
            throw new IllegalStateException("Cannot submit order: waiting for position info or instrument " + instrumentUid + " is locked");
        }

        // Установка блокировки по инструменту
        instrumentLocks.computeIfAbsent(instrumentUid, k -> new AtomicBoolean(false));
        AtomicBoolean instrumentLock = instrumentLocks.get(instrumentUid);
        if (!instrumentLock.compareAndSet(false, true)) {
            throw new IllegalStateException("Instrument " + instrumentUid + " is locked due to pending order");
        }

        String orderId = UUID.randomUUID().toString();
        OrderExecutionState state = new OrderExecutionState(
                orderId,
                instrumentUid,
                OrderDirection.ORDER_DIRECTION_SELL,
                quantity,
                BigDecimal.ZERO // Цена будет определена при исполнении
        );
        state.setStatus(OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_NEW);
        state.setPendingConfirmation(true);
        state.setMarketOrder(true);
        state.setWaitingForPositionInfo(true);

        activeOrders.put(orderId, state);
        scheduleStatusRecovery(orderId);

        // Установка глобальной блокировки
        waitingForPositionInfo.set(true);

        tradeExecutionService.postMarketOrderWithTracking(orderId, instrumentUid, quantity, OrderDirection.ORDER_DIRECTION_SELL);

        log.info("Created market sell order {}: {} x {}", orderId, instrumentUid, quantity);
        return state;
    }

    /**
     * Получить статус заявки
     */
    public OrderExecutionState getOrderState(String orderId) {
        return activeOrders.get(orderId);
    }

    /**
     * Получить все активные заявки
     */
    public List<OrderExecutionState> getActiveOrders() {
        return new ArrayList<>(activeOrders.values());
    }

    /**
     * Получить активные заявки по инструменту
     */
    public List<OrderExecutionState> getActiveOrdersByInstrument(String instrumentUid) {
        return activeOrders.values().stream()
                .filter(state -> state.getInstrumentUid().equals(instrumentUid))
                .toList();
    }

    // ========== Методы для TradeExecutionService ==========

    /**
     * Обновить статус заявки из стрима
     * Вызывается из TradeExecutionService.onNextOrder()
     */
    public void updateOrderStatus(OrderStateStreamResponse orderState) {
        if (!orderState.hasOrderState()) {
            return;
        }
        updateOrderStatus(orderState.getOrderState());
    }

    /**
     * Обновить статус заявки из стрима (внутренний метод)
     */
    void updateOrderStatus(OrderStateStreamResponse.OrderState orderState) {
        String orderId = orderState.getOrderRequestId();
        updateOrderStatusInternal(orderId, orderState.getExecutionReportStatus(),
                orderState.getLotsExecuted(), orderState.getInstrumentUid(), orderState.getDirection(),
                () -> {
                    try {
                        if (orderState.hasAmount()) {
                            return NumberMapper.moneyValueToBigDecimal(orderState.getAmount());
                        }
                    } catch (Exception e) {
                        log.debug("Could not get amount from stream order state: {}", e.getMessage());
                    }
                    return null;
                });
    }

    /**
     * Обновить статус заявки из API (при синхронизации)
     */
    void updateOrderStatus(OrderState apiState) {
        String orderId = apiState.getOrderRequestId();
        updateOrderStatusInternal(orderId, apiState.getExecutionReportStatus(),
                apiState.getLotsExecuted(), apiState.getInstrumentUid(), apiState.getDirection(),
                () -> {
                    try {
                        return NumberMapper.moneyValueToBigDecimal(apiState.getTotalOrderAmount());
                    } catch (Exception e) {
                        log.debug("Could not get amount from API order state: {}", e.getMessage());
                    }
                    return null;
                });
    }

    /**
     * Внутренний метод обновления статуса заявки
     */
    private void updateOrderStatusInternal(String orderId, OrderExecutionReportStatus newStatus,
                                           long lotsExecuted, String instrumentUid, OrderDirection direction,
                                           java.util.function.Supplier<BigDecimal> amountSupplier) {
        OrderExecutionState state = activeOrders.get(orderId);
        if (state == null) {
            log.warn("Received update for unknown order: {}. Attempting to restore from API.", orderId);
            restoreOrderFromApi(orderId);
            return;
        }

        OrderExecutionReportStatus oldStatus = state.getStatus();

        state.setStatus(newStatus);
        state.setUpdatedAt(Instant.now());
        state.setExecutedQuantity(lotsExecuted);

        try {
            BigDecimal amount = amountSupplier.get();
            if (amount != null) {
                state.setExecutedAmount(amount);
            }
        } catch (Exception e) {
            log.debug("Could not set executed amount: {}", e.getMessage());
        }

        state.setPendingConfirmation(false);
        state.setWaitingForPositionInfo(false);
        state.setRetryCount(0);

        log.info("Order {} status updated: {} -> {}", orderId, oldStatus, newStatus);

        // Если терминальный статус - переместить в завершённые
        if (state.isTerminalStatus()) {
            finishedOrders.checkContainsAndAdd(orderId);
            activeOrders.remove(orderId);

            // Снятие блокировки для market orders
            if (state.isMarketOrder()) {
                AtomicBoolean lock = instrumentLocks.get(instrumentUid);
                if (lock != null) {
                    lock.set(false);
                    log.debug("Released lock for instrument {} after market order {} completion", instrumentUid, orderId);
                }
            }

            // Снятие глобальной блокировки ожидания информации по позициям
            if (state.isWaitingForPositionInfo()) {
                waitingForPositionInfo.set(false);
                log.debug("Released global position info lock after order {} completion", orderId);
            }

            // Опубликовать событие о завершении
            if (newStatus == OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_FILL) {
                BigDecimal amount = state.getExecutedAmount();
                if (amount != null) {
                    eventPublisher.publishEvent(new TradeCompletedEvent(
                            this,
                            instrumentUid,
                            direction,
                            amount
                    ));
                    log.info("Trade completed for order {}: {} {} for {}",
                            orderId, direction, instrumentUid, amount);
                }
            }
        }
    }

    /**
     * Обработка разрыва соединения
     */
    public void handleConnectionLost() {
        streamConnected.set(false);
        log.warn("Stream connection lost. Marking {} active orders for reconciliation", activeOrders.size());

        activeOrders.values().forEach(state -> {
            if (!state.isTerminalStatus()) {
                state.setPendingConfirmation(true);
                scheduleStatusRecovery(state.getOrderId());
            }
        });
    }

    /**
     * Восстановление после подключения
     */
    public void handleConnectionRestored() {
        streamConnected.set(true);
        log.info("Stream connection restored. Reconciling active orders...");

        reconcileActiveOrders();
    }

    // ========== Внутренняя логика ==========

    private void scheduleStatusRecovery(String orderId) {
        long delay = properties.getOrderRecoveryDelayMs();
        recoveryExecutor.schedule(() -> {
            OrderExecutionState state = activeOrders.get(orderId);
            if (state != null && state.isPendingConfirmation()) {
                log.debug("Scheduled recovery check for order {}", orderId);
                syncOrderStatusFromApi(orderId);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void startPeriodicReconciliation() {
        long interval = properties.getOrderReconciliationIntervalMs();
        log.info("Starting periodic order reconciliation every {} ms", interval);

        recoveryExecutor.scheduleAtFixedRate(
                this::reconcileActiveOrders,
                interval,
                interval,
                TimeUnit.MILLISECONDS
        );
    }

    private void reconcileActiveOrders() {
        if (!streamConnected.get()) {
            log.debug("Skipping reconciliation - stream not connected");
            return;
        }

        List<String> ordersToSync = activeOrders.values().stream()
                .filter(state -> !state.isTerminalStatus())
                .filter(state -> state.isPendingConfirmation() || state.isStale(DEFAULT_STALE_THRESHOLD))
                .map(OrderExecutionState::getOrderId)
                .toList();

        if (!ordersToSync.isEmpty()) {
            log.info("Reconciling {} active orders: {}", ordersToSync.size(), ordersToSync);
            ordersToSync.forEach(this::syncOrderStatusFromApi);
        }
    }

    private void syncOrderStatusFromApi(String orderId) {
        OrderExecutionState state = activeOrders.get(orderId);
        if (state == null) return;

        try {
            OrderState apiState = tradeExecutionService.getOrderStateFromApi(orderId);
            if (apiState != null) {
                updateOrderStatus(apiState);
                state.setLastSyncAttempt(Instant.now());
                log.debug("Successfully synced order {} from API: {}", orderId, apiState.getExecutionReportStatus());
            } else {
                log.warn("Order {} not found in API", orderId);
                handleMissingOrder(state);
            }
        } catch (Exception e) {
            log.warn("Failed to sync order status {}: {}", orderId, e.getMessage());
            state.incrementRetryCount();

            if (state.getRetryCount() > properties.getMaxRecoveryRetries()) {
                // Оставляем последний известный статус, но логируем проблему
                log.error("Order {} failed to sync after {} retries. Last known status: {}",
                        orderId, properties.getMaxRecoveryRetries(), state.getStatus());
            }
        }
    }

    private void handleMissingOrder(OrderExecutionState state) {
        // Заявка не найдена в API - возможная потеря
        state.incrementRetryCount();

        if (state.getRetryCount() > properties.getMaxRecoveryRetries()) {
            log.error("Order {} not found after {} retries. Last known status: {}",
                    state.getOrderId(), properties.getMaxRecoveryRetries(), state.getStatus());
            // TODO: Уведомить оператора
        }
    }

    private void restoreOrderFromApi(String orderId) {
        // Попытка восстановить состояние заявки из API
        try {
            OrderState apiState = tradeExecutionService.getOrderStateFromApi(orderId);
            if (apiState != null) {
                OrderExecutionState state = createFromApiState(apiState);
                activeOrders.put(orderId, state);
                log.info("Restored order {} from API: {}", orderId, state.getStatus());
            } else {
                log.warn("Order {} not found in API during restoration", orderId);
            }
        } catch (Exception e) {
            log.error("Failed to restore order {} from API", orderId, e);
        }
    }

    private OrderExecutionState createFromApiState(OrderState apiState) {
        OrderExecutionState state = new OrderExecutionState(
                apiState.getOrderRequestId(),
                apiState.getInstrumentUid(),
                apiState.getDirection(),
                apiState.getLotsRequested(),
                NumberMapper.moneyValueToBigDecimal(apiState.getInitialOrderPrice())
        );
        state.setStatus(apiState.getExecutionReportStatus());
        state.setExecutedQuantity(apiState.getLotsExecuted());
        state.setUpdatedAt(Instant.now());

        // Amount may not be available for all order states
        try {
            state.setExecutedAmount(NumberMapper.moneyValueToBigDecimal(apiState.getTotalOrderAmount()));
        } catch (Exception e) {
            log.debug("Could not get amount from API state: {}", e.getMessage());
        }

        return state;
    }

    /**
     * Получить количество активных заявок
     */
    public int getActiveOrdersCount() {
        return activeOrders.size();
    }

    /**
     * Проверка, подключен ли стрим
     */
    public boolean isStreamConnected() {
        return streamConnected.get();
    }
}
