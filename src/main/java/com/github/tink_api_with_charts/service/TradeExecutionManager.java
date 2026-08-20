package com.github.tink_api_with_charts.service;

import com.github.tink_api_with_charts.cinfiguration.BalancerProperties;
import com.github.tink_api_with_charts.entity.OrderExecutionState;
import com.github.tink_api_with_charts.event.PositionInfoUpdatedEvent;
import com.github.tink_api_with_charts.event.ConnectionRestoredEvent;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Менеджер управления заявками на исполнение
 * Отвечает за отслеживание статуса заявок и восстановление после разрывов соединения
 */
@Service
public class TradeExecutionManager {

    private static final Logger log = LoggerFactory.getLogger(TradeExecutionManager.class);
    private static final Duration DEFAULT_STALE_THRESHOLD = Duration.ofMinutes(2);
    private static final ZoneId ZONE_ID = ZoneId.of("Europe/Moscow");
    private static final LocalTime SESSION_START = LocalTime.of(7, 0, 0);
    private static final LocalTime SESSION_END = LocalTime.of(23, 48, 0);

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
    private final AtomicBoolean globalLock = new AtomicBoolean(false);
    private final AtomicBoolean limitOrdersAreSet = new AtomicBoolean(false);

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
        globalLock.set(false);
        log.debug("Released global position lock after PositionInfoUpdatedEvent");

    }

    public void releaseGlobalLock(boolean releaseLock) {
        if (releaseLock) {
            globalLock.set(false);
            log.debug("Released global lock");
        }
    }

    private void setGlobalLock() {
        globalLock.set(true);
        log.debug("Set global lock");

    }

    // ========== Публичные методы для BalancerService ==========

    /**
     * Отправить заявку на покупку
     * @return OrderExecutionState для отслеживания статуса
     */
    public OrderExecutionState submitLimitBuyOrder(String instrumentUid, BigDecimal price, long quantity) {
        // Проверка блокировки
//        if (!canSubmitMarketOrder(instrumentUid)) {
//            log.warn("Cannot submit buy order: waiting for position info or instrument {} is locked", instrumentUid);
//            throw new IllegalStateException("Cannot submit order: waiting for position info or instrument " + instrumentUid + " is locked");
//        }

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
//        state.setWaitingForPositionInfo(true);

        activeOrders.put(orderId, state);
        scheduleStatusRecovery(orderId);

        // Установка глобальной блокировки
//        waitingForPositionInfo.set(true);

        tradeExecutionService.postLimitOrderWithTracking(orderId, instrumentUid, price, quantity, OrderDirection.ORDER_DIRECTION_BUY);

        log.info("Created buy order {}: {} x {} @ {}", orderId, instrumentUid, quantity, price);
        return state;
    }

    /**
     * Отправить заявку на продажу
     * @return OrderExecutionState для отслеживания статуса
     */
    public OrderExecutionState submitLimitSellOrder(String instrumentUid, BigDecimal price, long quantity) {
        // Проверка блокировки
//        if (!canSubmitMarketOrder(instrumentUid)) {
//            log.warn("Cannot submit sell order: waiting for position info or instrument {} is locked", instrumentUid);
//            throw new IllegalStateException("Cannot submit order: waiting for position info or instrument " + instrumentUid + " is locked");
//        }

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
//        state.setWaitingForPositionInfo(true);

        activeOrders.put(orderId, state);
        scheduleStatusRecovery(orderId);

        // Установка глобальной блокировки
//        waitingForPositionInfo.set(true);

        tradeExecutionService.postLimitOrderWithTracking(orderId, instrumentUid, price, quantity, OrderDirection.ORDER_DIRECTION_SELL);

        log.info("Created sell order {}: {} x {} @ {}", orderId, instrumentUid, quantity, price);
        return state;
    }

    /**
     * Отправить market заявку на покупку (с блокировкой инструмента)
     */
    public boolean submitMarketBuyOrder(String instrumentUid, long quantity) {
        if (!isTradingTime()) {
            log.warn("Market is closed");
            return false;
        }
        // Проверка блокировки
        if (!checkActiveLimitOrders(instrumentUid)) {
            log.warn("MarketBuyOrder Instrument {} is locked due to active limit orders", instrumentUid);
            return false;
        }
        if (globalLock.get()) {
            log.warn("Cannot submit MarketBuyOrder order: waiting for position info for instrument {}", instrumentUid);
            return false;
        }
        if (!checkInstrumentLock(instrumentUid)) {
            log.warn("MarketBuyOrder Instrument {} is locked due to pending order", instrumentUid);
            return false;
        }
        setGlobalLock();
        OrderExecutionState order = prepareOrder(instrumentUid, quantity, OrderDirection.ORDER_DIRECTION_BUY, true);
        // Установка глобальной блокировки

        String tradeIntentId = tradeExecutionService.postMarketOrderWithTracking(order.getRequestId(), instrumentUid, quantity, OrderDirection.ORDER_DIRECTION_BUY);
        order.setTradeIntentId(tradeIntentId);
        log.info("Created market buy order {}: {} x {}", order.getRequestId(), instrumentUid, quantity);
        return true;
    }

    /**
     * Отправить market заявку на продажу (с блокировкой инструмента)
     */
    public boolean submitMarketSellOrder(String instrumentUid, long quantity) {
        if (!isTradingTime()) {
            log.warn("Market is closed");
            return false;
        }
        // Проверка блокировки
        if (!checkActiveLimitOrders(instrumentUid)) {
            log.warn("MarketSellOrder Instrument {} is locked due to active limit orders", instrumentUid);
            return false;
        }
        if (globalLock.get()) {
            log.warn("MarketSellOrder Cannot submit order: waiting for position info for instrument {}", instrumentUid);
            return false;
        }
        if (!checkInstrumentLock(instrumentUid)) {
            log.warn("MarketSellOrder Instrument {} is locked due to pending order", instrumentUid);
            return false;
        }
        setGlobalLock();
        OrderExecutionState order = prepareOrder(instrumentUid, quantity, OrderDirection.ORDER_DIRECTION_SELL, true);
        // Установка глобальной блокировки

        String tradeIntentId = tradeExecutionService.postMarketOrderWithTracking(order.getRequestId(), instrumentUid, quantity, OrderDirection.ORDER_DIRECTION_SELL);
        order.setTradeIntentId(tradeIntentId);
        log.info("Created market sell order {}: {} x {}", order.getRequestId(), instrumentUid, quantity);
        return true;
    }

    public synchronized void submitBalancerLimitOrders(String instrumentUid, BigDecimal priceBuy, long qtyBuy, BigDecimal priceSell, long qtySell) {
        if (!isTradingTime()) {
            log.warn("Market is closed");
            return;
        }
        if (globalLock.get()) {
            log.warn("BalancerLimitOrders Cannot submit order: waiting for position info for instrument {}", instrumentUid);
            return;
        }
//        if (!checkInstrumentLock(instrumentUid)) {
//            log.warn("BalancerLimitOrders Instrument {} is locked due to pending order", instrumentUid);
//            return;
//        }
        try {
            tradeExecutionService.cancelOpenedOrdersForInstrument(instrumentUid);

            OrderExecutionState buyOrder = prepareOrder(instrumentUid, qtyBuy, OrderDirection.ORDER_DIRECTION_BUY, false);
            String buyTradeIntentId = tradeExecutionService.checkedLimitBuy(buyOrder.getRequestId(), instrumentUid, priceBuy, qtyBuy);
            if (buyTradeIntentId == null) {
                activeOrders.remove(buyOrder.getRequestId());
            }
            buyOrder.setTradeIntentId(buyTradeIntentId);

            OrderExecutionState sellOrder = prepareOrder(instrumentUid, qtySell, OrderDirection.ORDER_DIRECTION_SELL, false);
            String sellTradeIntentId = tradeExecutionService.checkedLimitSell(sellOrder.getRequestId(), instrumentUid, priceSell, qtySell);
            if (sellTradeIntentId == null) {
                activeOrders.remove(sellOrder.getRequestId());
            }
            sellOrder.setTradeIntentId(sellTradeIntentId);
        } finally {
//            instrumentLocks.get(instrumentUid).set(false);
        }
    }

    private OrderExecutionState prepareOrder(String instrumentUid, long qtyBuy, OrderDirection orderDirection, boolean marketOrder) {
        String orderId = UUID.randomUUID().toString();
        OrderExecutionState state = new OrderExecutionState(
                orderId,
                instrumentUid,
                orderDirection,
                qtyBuy,
                BigDecimal.ZERO // Цена будет определена при исполнении
        );
        state.setStatus(OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_NEW);
        state.setPendingConfirmation(true);
        state.setMarketOrder(marketOrder);
        state.setWaitingForPositionInfo(marketOrder);

        activeOrders.put(orderId, state);
        scheduleStatusRecovery(orderId);
        return state;
    }

    private boolean checkInstrumentLock(String instrumentUid) {
        // Установка блокировки по инструменту
        instrumentLocks.computeIfAbsent(instrumentUid, k -> new AtomicBoolean(false));
        AtomicBoolean instrumentLock = instrumentLocks.get(instrumentUid);
        if (!instrumentLock.compareAndSet(false, true)) {
            return false;
        }
        return true;
    }

    private boolean checkActiveLimitOrders(String instrumentUid) {
        long activeLimitBuyOrdersCount = activeOrders.values().stream()
                .filter(orderExecutionState -> !orderExecutionState.isMarketOrder())
                .filter(orderExecutionState -> !orderExecutionState.isTerminalStatus())
                .filter(orderExecutionState -> orderExecutionState.getDirection().equals(OrderDirection.ORDER_DIRECTION_BUY))
                .count();
        long activeLimitSellOrdersCount = activeOrders.values().stream()
                .filter(orderExecutionState -> !orderExecutionState.isMarketOrder())
                .filter(orderExecutionState -> !orderExecutionState.isTerminalStatus())
                .filter(orderExecutionState -> orderExecutionState.getDirection().equals(OrderDirection.ORDER_DIRECTION_SELL))
                .count();
        if (activeLimitBuyOrdersCount > 0 && activeLimitSellOrdersCount > 0) {
            return false;
        }
        return true;
    }

    private boolean isTradingTime() {
        // Получаем текущее время в московском часовом поясе
        LocalTime currentTime = LocalTime.now(ZONE_ID);
        // Проверяем торговые сессии
        return !currentTime.isBefore(SESSION_START) &&
               !currentTime.isAfter(SESSION_END);
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
        String orderRequestId = orderState.getOrderRequestId();
        String tradeIntentId = orderState.getOrderId();
        updateOrderStatusInternal(orderRequestId, tradeIntentId, orderState.getExecutionReportStatus(),
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
        updateOrderStatusInternal(orderId, null, apiState.getExecutionReportStatus(),
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
    private void updateOrderStatusInternal(String orderRequestId, String tradeIntentId, OrderExecutionReportStatus newStatus,
                                           long lotsExecuted, String instrumentUid, OrderDirection direction,
                                           Supplier<BigDecimal> amountSupplier) {
        OrderExecutionState state = activeOrders.get(orderRequestId);
        if (state == null) {
            log.warn("Received update for unknown order: {}. Attempting to restore from API.", orderRequestId);
            restoreOrderFromApi(orderRequestId, tradeIntentId);
            return;
        }

        OrderExecutionReportStatus oldStatus = state.getStatus();

        state.setStatus(newStatus);
        state.setUpdatedAt(Instant.now());
        state.setExecutedQuantity(lotsExecuted);
        if (tradeIntentId != null) {
            state.setTradeIntentId(tradeIntentId);
        }

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

        log.info("Order {} status updated: {} -> {}", orderRequestId, oldStatus, newStatus);

        // Если терминальный статус - переместить в завершённые
        if (state.isTerminalStatus()) {
            finishedOrders.checkContainsAndAdd(orderRequestId);
            activeOrders.remove(orderRequestId);

            // Снятие блокировки для market orders
            if (state.isMarketOrder()) {
                AtomicBoolean lock = instrumentLocks.get(instrumentUid);
                if (lock != null) {
                    lock.set(false);
                    log.debug("Released lock for instrument {} after market order {} completion", instrumentUid, orderRequestId);
                }
            }

//            // Снятие глобальной блокировки ожидания информации по позициям
//            if (state.isWaitingForPositionInfo()) {
//                waitingForPositionInfo.set(false);
//                log.debug("Released global position info lock after order {} completion", orderRequestId);
//            }

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
                            orderRequestId, direction, instrumentUid, amount);
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
                scheduleStatusRecovery(state.getRequestId());
            }
        });
    }

    /**
     * Восстановление после подключения
     */
    public void handleConnectionRestored() {
        streamConnected.set(true);
        log.info("Stream connection restored. Reconciling active orders...");
        eventPublisher.publishEvent(new ConnectionRestoredEvent(this));
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
                .map(OrderExecutionState::getRequestId)
                .toList();

        if (!ordersToSync.isEmpty()) {
            log.info("Reconciling {} active orders: {}", ordersToSync.size(), ordersToSync);
            ordersToSync.forEach(this::syncOrderStatusFromApi);
        }
    }

    private void syncOrderStatusFromApi(String orderRequestId) {
        OrderExecutionState state = activeOrders.get(orderRequestId);
        if (state == null) return;

        try {
            OrderState openOrderFromApi = tradeExecutionService.getOpenOrderFromApi(orderRequestId);
            if (openOrderFromApi != null) {
                updateOrderStatus(openOrderFromApi);
                state.setLastSyncAttempt(Instant.now());
                log.debug("Successfully synced order {} from API: {}", orderRequestId, openOrderFromApi.getExecutionReportStatus());
            } else {
                log.warn("Order {} not found in open orders API. Will try to fetch from get order state API by trade ID {}", orderRequestId, state.getTradeIntentId());
                if (state.getTradeIntentId() != null) {
                    OrderState orderStateFromApi = tradeExecutionService.getOrderStateFromApi(state.getTradeIntentId());
                    updateOrderStatus(orderStateFromApi);
                }
                handleMissingOrder(state);
            }
        } catch (Exception e) {
            log.warn("Failed to sync order status {}: {}", orderRequestId, e.getMessage());
            state.incrementRetryCount();

            if (state.getRetryCount() > properties.getMaxRecoveryRetries()) {
                // Оставляем последний известный статус, но логируем проблему
                log.error("Order {} failed to sync after {} retries. Last known status: {}. Removing from active orders",
                        orderRequestId, properties.getMaxRecoveryRetries(), state.getStatus());
                activeOrders.remove(state.getRequestId());
            }
        }
    }

    private void handleMissingOrder(OrderExecutionState state) {
        // Заявка не найдена в API - возможная потеря
        state.incrementRetryCount();

        if (state.getRetryCount() > properties.getMaxRecoveryRetries()) {
            log.error("Order {} not found after {} retries. Last known status: {}. Removing from active orders",
                    state.getRequestId(), properties.getMaxRecoveryRetries(), state.getStatus());
            activeOrders.remove(state.getRequestId());
        }
    }

    private void restoreOrderFromApi(String orderRequestId, String tradeIntentId) {
        // Попытка восстановить состояние заявки из API
        try {
            OrderState stateFromOpenOrderApi = tradeExecutionService.getOpenOrderFromApi(orderRequestId);
            if (stateFromOpenOrderApi != null) {
                OrderExecutionState state = createFromApiState(stateFromOpenOrderApi);
                activeOrders.put(orderRequestId, state);
                log.info("Restored order {} from open orders API: {}", orderRequestId, state.getStatus());
            } else {
                log.warn("Order {} not found in open orders API during restoration", orderRequestId);
                OrderState orderStateFromApi = tradeExecutionService.getOrderStateFromApi(tradeIntentId);
                if (orderStateFromApi != null) {
                    OrderExecutionState state = createFromApiState(orderStateFromApi);
                    activeOrders.put(orderRequestId, state);
                    log.info("Restored order {} from all orders API: {}", orderRequestId, state.getStatus());
                } else {
                    log.warn("Order {} not found in all orders API during restoration", orderRequestId);
                }
            }
        } catch (Exception e) {
            log.error("Failed to restore order {} from API", orderRequestId, e);
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
