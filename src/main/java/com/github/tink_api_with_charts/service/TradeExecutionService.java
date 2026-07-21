package com.github.tink_api_with_charts.service;

import com.github.tink_api_with_charts.cinfiguration.BalancerProperties;
import com.github.tink_api_with_charts.event.PositionInfoUpdatedEvent;
import com.github.tink_api_with_charts.event.TradeCompletedEvent;
import com.github.tink_api_with_charts.utils.ConcurrentSlidingCache;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import ru.tinkoff.piapi.contract.v1.CancelOrderRequest;
import ru.tinkoff.piapi.contract.v1.CandleInstrument;
import ru.tinkoff.piapi.contract.v1.GetMaxLotsRequest;
import ru.tinkoff.piapi.contract.v1.GetOrdersRequest;
import ru.tinkoff.piapi.contract.v1.GetOrdersResponse;
import ru.tinkoff.piapi.contract.v1.InstrumentIdType;
import ru.tinkoff.piapi.contract.v1.InstrumentRequest;
import ru.tinkoff.piapi.contract.v1.InstrumentsServiceGrpc;
import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.OperationsServiceGrpc;
import ru.tinkoff.piapi.contract.v1.OrderDirection;
import ru.tinkoff.piapi.contract.v1.OrderExecutionReportStatus;
import ru.tinkoff.piapi.contract.v1.OrderIdType;
import ru.tinkoff.piapi.contract.v1.OrderState;
import ru.tinkoff.piapi.contract.v1.OrderStateStreamRequest;
import ru.tinkoff.piapi.contract.v1.OrderStateStreamResponse;
import ru.tinkoff.piapi.contract.v1.OrderType;
import ru.tinkoff.piapi.contract.v1.OrdersServiceGrpc;
import ru.tinkoff.piapi.contract.v1.PostOrderAsyncRequest;
import ru.tinkoff.piapi.contract.v1.SandboxServiceGrpc;
import ru.tinkoff.piapi.contract.v1.UsersServiceGrpc;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;
import ru.ttech.piapi.core.connector.ServiceStubFactory;
import ru.ttech.piapi.core.connector.SyncStubWrapper;
import ru.ttech.piapi.core.connector.streaming.StreamServiceStubFactory;
import ru.ttech.piapi.core.helpers.NumberMapper;
import ru.ttech.piapi.core.impl.orders.OrderStateStreamWrapperConfiguration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Данный класс является примером реализации сервиса, который отвечает за торговлю на бирже согласно сигналам по стратегии
 */
@Service
public class TradeExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TradeExecutionService.class);

    private final ConnectorConfiguration configuration;
    private final BalancerProperties properties;
    private final SyncStubWrapper<UsersServiceGrpc.UsersServiceBlockingStub> userService;
    private final SyncStubWrapper<InstrumentsServiceGrpc.InstrumentsServiceBlockingStub> instrumentsService;
    private final SyncStubWrapper<OrdersServiceGrpc.OrdersServiceBlockingStub> ordersService;
    private final SyncStubWrapper<SandboxServiceGrpc.SandboxServiceBlockingStub> sandboxService;
    private final SyncStubWrapper<OperationsServiceGrpc.OperationsServiceBlockingStub> operationsService;
    private final ScheduledExecutorService streamHealthcheckExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ServiceStubFactory serviceStubFactory;
    private final StreamServiceStubFactory streamServiceStubFactory;
    private final ApplicationEventPublisher eventPublisher;

    private final BigDecimal sandboxBalance;
    private final Set<String> instruments;
    private final int instrumentLots;
    private final String tradingAccountId;

    private final AtomicReference<BigDecimal> shareBuyPrice = new AtomicReference<>();
    private final AtomicLong shareBuyQty = new AtomicLong(0);
    private final AtomicReference<BigDecimal> shareSellPrice = new AtomicReference<>();
    private final AtomicLong shareSellQty = new AtomicLong(0);
    private final AtomicBoolean isWaitingForPositionInfo = new AtomicBoolean(false);

    private final Map<String, String> instrumentLastOrderIds = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> uidToMinPriceIncrement = new ConcurrentHashMap<>();
    private final Map<String, PostOrderAsyncRequest> uidToPendingOrderId = new ConcurrentHashMap<>();
    private final ConcurrentSlidingCache<String> newOrdersCache = new ConcurrentSlidingCache<>();
    private final ConcurrentSlidingCache<String> filledOrdersCache = new ConcurrentSlidingCache<>();
    private final ConcurrentSlidingCache<String> cancelledOrdersCache = new ConcurrentSlidingCache<>();

    public TradeExecutionService(
            ServiceStubFactory serviceStubFactory,
            BalancerProperties properties,
            StreamServiceStubFactory streamServiceStubFactory,
            ApplicationEventPublisher eventPublisher
    ) {
        this.configuration = serviceStubFactory.getConfiguration();
        this.properties = properties;
        this.streamServiceStubFactory = streamServiceStubFactory;
        this.eventPublisher = eventPublisher;
        this.sandboxBalance = BigDecimal.TEN;
        this.instrumentLots = 1;
        this.tradingAccountId = properties.getAccountId();
        this.serviceStubFactory = serviceStubFactory;
        this.instruments = Set.of(properties.getShareUid(), properties.getCashEtfUid());
        this.userService = serviceStubFactory.newSyncService(UsersServiceGrpc::newBlockingStub);
        this.instrumentsService = serviceStubFactory.newSyncService(InstrumentsServiceGrpc::newBlockingStub);
        this.ordersService = serviceStubFactory.newSyncService(OrdersServiceGrpc::newBlockingStub);
        this.sandboxService = serviceStubFactory.newSyncService(SandboxServiceGrpc::newBlockingStub);
        this.operationsService = serviceStubFactory.newSyncService(OperationsServiceGrpc::newBlockingStub);
    }

    @PostConstruct
    public void start() {
        var wrapper = streamServiceStubFactory.newResilienceServerSideStream(
                OrderStateStreamWrapperConfiguration.builder(streamHealthcheckExecutor)
                        .addOnResponseListener(this::onNextOrder)
                        .addOnConnectListener(() -> {
                            log.info("Стрим ордеров успешно подключен");
                            initOrders();
                        })
                        .build());

        var request = OrderStateStreamRequest.newBuilder()
                .addAccounts(tradingAccountId)
                .build();
        wrapper.subscribe(request);

        initOrders();
    }

    private void initOrders() {
        var ordersRequest = GetOrdersRequest.newBuilder()
                .setAccountId(tradingAccountId)
                .build();
        GetOrdersResponse ordersResponse = ordersService.callSyncMethod(stub -> stub.getOrders(ordersRequest));
        List<OrderState> ordersList = ordersResponse.getOrdersList();
        log.info("ordersResponse.getOrdersList() = {}", ordersList);
        ordersList.stream()
                .filter(orderState -> orderState.getInstrumentUid().equals(properties.getShareUid()))
                .filter(orderState -> orderState.getExecutionReportStatus().equals(OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_NEW))
                .forEach(orderState -> {
                    MoneyValue moneyValue = orderState.getInitialSecurityPrice();
                    BigDecimal price = NumberMapper.moneyValueToBigDecimal(moneyValue);
                    long lots = orderState.getLotsRequested() - orderState.getLotsExecuted();
                    switch (orderState.getDirection()) {
                        case ORDER_DIRECTION_BUY -> {
                            updateShareBuyOrder(lots, price);
                        }
                        case ORDER_DIRECTION_SELL -> {
                            updateShareSellOrder(lots, price);
                        }
                        default -> log.warn("Unrecognized order direction {}", orderState);
                    }
                });
    }

    private void updateShareSellOrder(long lots, BigDecimal price) {
        shareSellPrice.set(price);
        shareSellQty.set(lots);
        log.info("New share Sell order: price {}, lots {}", price, lots);
    }

    private void updateShareBuyOrder(long lots, BigDecimal price) {
        shareBuyPrice.set(price);
        shareBuyQty.set(lots);
        log.info("New share Buy order: price {}, lots {}", price, lots);
    }

    private void onNextOrder(OrderStateStreamResponse orderState) {
        if (!orderState.hasOrderState()) {
            return;
        }
        var order = orderState.getOrderState();
        if (order.getExecutionReportStatus() == OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_NEW
            && newOrdersCache.checkContainsAndAdd(order.getOrderRequestId())) {
            return;
        }
        if (order.getExecutionReportStatus() == OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_FILL
            && filledOrdersCache.checkContainsAndAdd(order.getOrderRequestId())) {
            return;
        }
        if (order.getExecutionReportStatus() == OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_CANCELLED
            && cancelledOrdersCache.checkContainsAndAdd(order.getOrderRequestId())) {
            return;
        }
        log.info("{}", orderState);
        var instrumentId = order.getInstrumentUid();
        log.info("Новый ордер с id: {}", order.getOrderRequestId());
        if (order.getExecutionReportStatus() == OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_FILL) {
            log.info("Сделка {} исполнена", order.getOrderRequestId());
            if (order.getDirection() == OrderDirection.ORDER_DIRECTION_BUY) {
                var buyAmount = NumberMapper.moneyValueToBigDecimal(order.getAmount());
                log.info("Заявка на покупку инструмента {} исполнена! Стоимость ордера: {}", instrumentId, buyAmount);
            } else if (order.getDirection() == OrderDirection.ORDER_DIRECTION_SELL) {
                var sellAmount = NumberMapper.moneyValueToBigDecimal(order.getAmount());
                log.info("Заявка на продажу инструмента {} исполнена! Стоимость ордера: {}", instrumentId, sellAmount);
            }
            BigDecimal amount = NumberMapper.moneyValueToBigDecimal(order.getAmount());
            // Опубликовать событие
            eventPublisher.publishEvent(new TradeCompletedEvent(
                    this,
                    order.getInstrumentUid(),
                    order.getDirection(),
                    amount
            ));
        }
        if (Set.of(OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_CANCELLED,
                OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_FILL,
                OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_REJECTED).contains(order.getExecutionReportStatus())) {
            uidToPendingOrderId.remove(order.getInstrumentUid());
            log.info("По инструменту {} ( uid {}) исполнена или отменена заявка {}", order.getTicker(), instrumentId, order.getOrderRequestId());
        }
        if (!order.getInstrumentUid().equals(properties.getShareUid())) {
            log.info("Not share order");
            return;
        }
        if (!order.getExecutionReportStatus().equals(OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_NEW)) {
            return;
        }
        MoneyValue moneyValue = order.getInitialOrderPrice();
        BigDecimal price = NumberMapper.moneyValueToBigDecimal(moneyValue);
        long lots = order.getLotsRequested() - order.getLotsExecuted();
        switch (order.getDirection()) {
            case ORDER_DIRECTION_BUY -> {
                updateShareBuyOrder(lots, price);
            }
            case ORDER_DIRECTION_SELL -> {
                updateShareSellOrder(lots, price);
            }
            default -> log.warn("Unrecognized order direction {}", orderState);
        }
    }

    public void marketBuy(String instrumentId, BigDecimal maxBuyPrice, long qty) {
//        cancelOpenedOrdersForInstrument(instrumentId);
        var price = getInstrumentPrice(instrumentId, maxBuyPrice, OrderDirection.ORDER_DIRECTION_BUY);
        long quantity = Math.min(qty, getMaxBuyLots(instrumentId, price));
        if (quantity < qty) {
            log.warn("Недостаточно средств для покупки");
            return;
        }
        postLimitOrder(instrumentId, OrderDirection.ORDER_DIRECTION_BUY, quantity, price);
        log.info("Покупка по стратегии: {} по цене: {} (лотов: {})", instrumentId, price, quantity);
    }

    public void marketSell(String instrumentId, BigDecimal minSellPrice, long qty) {
//        cancelOpenedOrdersForInstrument(instrumentId);
        long quantity = Math.min(qty, getMaxSellLots(instrumentId));
        var price = getInstrumentPrice(instrumentId, minSellPrice, OrderDirection.ORDER_DIRECTION_SELL);
        if (quantity <= 0) {
            log.warn("Недостаточно средств на продажу");
            return;
        }
        postLimitOrder(instrumentId, OrderDirection.ORDER_DIRECTION_SELL, quantity, price);
        log.info("Продажа по стратегии: {} по цене: {} (лотов: {})", instrumentId, price, quantity);
    }

    /**
     * Вызывается при входе по стратегии
     *
     * @param instrument инструмент
     * @param bar        бар, на котором произошёл сигнал на вход по стратегии
     */
    public void onStrategyEnter(CandleInstrument instrument, Bar bar) {
        String instrumentId = instrument.getInstrumentId();
        var closePrice = bar.getClosePrice().bigDecimalValue();
        cancelOpenedOrdersForInstrument(instrumentId);
        var price = getInstrumentPrice(instrumentId, closePrice, OrderDirection.ORDER_DIRECTION_BUY);
        long quantity = Math.min(instrumentLots, getMaxBuyLots(instrumentId, price));
        if (quantity < instrumentLots) {
            log.warn("Недостаточно лотов для открытия сделки");
            return;
        }
        postLimitOrder(instrument.getInstrumentId(), OrderDirection.ORDER_DIRECTION_BUY, quantity, price);
        log.info("Вход по стратегии: {} по цене: {} (лотов: {})", instrument.getInstrumentId(), price, quantity);
    }

    /**
     * Вызывается при выходе по стратегии
     *
     * @param instrument инструмент
     * @param bar        бар, на котором произошёл сигнал на выход по стратегии
     */
    public void onStrategyExit(CandleInstrument instrument, Bar bar) {
        String instrumentId = instrument.getInstrumentId();
        var closePrice = bar.getClosePrice().bigDecimalValue();
        cancelOpenedOrdersForInstrument(instrumentId);
        long quantity = getMaxSellLots(instrumentId);
        var price = getInstrumentPrice(instrumentId, closePrice, OrderDirection.ORDER_DIRECTION_SELL);
        if (quantity <= 0) {
            log.warn("Недостаточно лотов для открытия сделки на продажу");
            return;
        }
        postLimitOrder(instrument.getInstrumentId(), OrderDirection.ORDER_DIRECTION_SELL, quantity, price);
        log.info("Выход по стратегии: {} по цене: {} (лотов: {})", instrument.getInstrumentId(), price, quantity);
    }

    /**
     * Метод для выставления лимитной заявки на покупку/продажу инструмента
     *
     * @param instrumentId - идентификатор инструмента
     * @param direction    - направление сделки
     * @param quantity     - количество лотов инструмента
     * @param price        - цена инструмента
     */
    private void postLimitOrder(String instrumentId, OrderDirection direction, long quantity, BigDecimal price) {
        if (Optional.ofNullable(tradingAccountId).isEmpty()) {
            throw new IllegalStateException("Нельзя выставить ордер, так как не указан брокерский счет");
        }
        if (uidToPendingOrderId.containsKey(instrumentId)) {
            log.info("Выставление нового ордера невозможно, пока есть открытые ордеры по инструменту: {}", uidToPendingOrderId.get(instrumentId));
            return;
        }
        if (isWaitingForPositionInfo.get()) {
            log.info("Выставление нового ордера невозможно, пока идёт обновление информации по позициям: {}", uidToPendingOrderId.get(instrumentId));
            return;
        }
        var postOrderRequest = PostOrderAsyncRequest.newBuilder()
                .setOrderId(UUID.randomUUID().toString())
                .setAccountId(tradingAccountId)
                .setInstrumentId(instrumentId)
                .setDirection(direction)
                .setQuantity(quantity)
                .setPrice(NumberMapper.bigDecimalToQuotation(price))
                .setOrderType(OrderType.ORDER_TYPE_LIMIT)
                .build();
//        instrumentLastOrderIds.put(instrumentId, order.getOrderRequestId());
        uidToPendingOrderId.put(instrumentId, postOrderRequest);
        isWaitingForPositionInfo.set(true);
        var order = ordersService.callSyncMethod(stub -> stub.postOrderAsync(postOrderRequest));
    }

    /**
     * Метод для отмены всех открытых ордеров по инструменту
     *
     * @param instrumentId инструмент
     */
    private void cancelOpenedOrdersForInstrument(String instrumentId) {
        var ordersRequest = GetOrdersRequest.newBuilder()
                .setAccountId(tradingAccountId)
                .build();
        var ordersResponse = ordersService.callSyncMethod(stub -> stub.getOrders(ordersRequest));

        var cancelOrderRequestBuilder = CancelOrderRequest.newBuilder()
                .setAccountId(tradingAccountId)
                .setOrderIdType(OrderIdType.ORDER_ID_TYPE_EXCHANGE);
        ordersResponse.getOrdersList().stream()
                .filter(orderState -> orderState.getInstrumentUid().equals(instrumentId))
                .forEach(order -> ordersService.callSyncMethod(stub ->
                        stub.cancelOrder(cancelOrderRequestBuilder.setOrderId(order.getOrderId()).build())
                ));
        log.info("Отменены все открытые ордера по инструменту {}", instrumentId);
    }

    /**
     * Метод для получения максимального количества лотов, доступных к покупке
     *
     * @param instrumentId идентификатор инструмента
     * @param price        цена инструмента
     * @return количество лотов инструмента
     */
    private long getMaxBuyLots(String instrumentId, BigDecimal price) {
        var getMaxLotsRequest = GetMaxLotsRequest.newBuilder()
                .setAccountId(tradingAccountId)
                .setInstrumentId(instrumentId)
                .setPrice(NumberMapper.bigDecimalToQuotation(price))
                .build();
        var response = ordersService.callSyncMethod(stub -> stub.getMaxLots(getMaxLotsRequest));
        return response.getBuyLimits().getBuyMaxLots();
    }

    /**
     * Метод для получения максимального количества лотов, доступных к продаже
     *
     * @param instrumentId идентификатор инструмента
     * @return количество лотов инструмента
     */
    private long getMaxSellLots(String instrumentId) {
        var getMaxLotsRequest = GetMaxLotsRequest.newBuilder()
                .setAccountId(tradingAccountId)
                .setInstrumentId(instrumentId)
                .build();
        var response = ordersService.callSyncMethod(stub -> stub.getMaxLots(getMaxLotsRequest));
        return response.getSellLimits().getSellMaxLots();
    }

    /**
     * Метод для расчёта цены инструмента исходя из направления сделки
     *
     * @param instrumentId идентификатор инструмента
     * @param price        цена инструмента
     * @param direction    направление сделки
     * @return цена инструмента
     */
    private BigDecimal getInstrumentPrice(String instrumentId, BigDecimal price, OrderDirection direction) {
        var minPriceIncrement = getMinPriceIncrement(instrumentId);
        return switch (direction) {
            case ORDER_DIRECTION_BUY -> roundDownPrice(price, minPriceIncrement);
            case ORDER_DIRECTION_SELL -> roundUpPrice(price, minPriceIncrement);
            default -> throw new IllegalArgumentException("Неизвестное направление ордера");
        };
    }

    /**
     * Метод для получения минимального шага цены по инструменту
     *
     * @param instrumentId идентификатор инструмента
     * @return минимальный шаг цены
     */
    private BigDecimal getMinPriceIncrement(String instrumentId) {
        if (uidToMinPriceIncrement.containsKey(instrumentId)) {
            return uidToMinPriceIncrement.get(instrumentId);
        }
        var instrumentRequest = InstrumentRequest.newBuilder()
                .setIdType(InstrumentIdType.INSTRUMENT_ID_TYPE_UID)
                .setId(instrumentId)
                .build();
        var instrumentResponse = instrumentsService.callSyncMethod(stub -> stub.getInstrumentBy(instrumentRequest));
        BigDecimal minPriceIncrement = NumberMapper.quotationToBigDecimal(instrumentResponse.getInstrument().getMinPriceIncrement());
        uidToMinPriceIncrement.put(instrumentId, minPriceIncrement);
        return minPriceIncrement;
    }

    /**
     * Метод для округления цены инструмента вверх
     *
     * @param price             цена инструмента
     * @param minPriceIncrement минимальный шаг цены инструмента
     * @return округленная цена инструмента
     */
    private BigDecimal roundUpPrice(BigDecimal price, BigDecimal minPriceIncrement) {
        return price.divide(minPriceIncrement, 0, RoundingMode.UP).multiply(minPriceIncrement);
    }

    /**
     * Метод для округления цены инструмента вниз
     *
     * @param price             цена инструмента
     * @param minPriceIncrement минимальный шаг цены инструмента
     * @return округленная цена инструмента
     */
    private BigDecimal roundDownPrice(BigDecimal price, BigDecimal minPriceIncrement) {
        return price.divide(minPriceIncrement, 0, RoundingMode.DOWN).multiply(minPriceIncrement);
    }

    @EventListener
    public void onPositionInfoUpdated(PositionInfoUpdatedEvent event) {
        isWaitingForPositionInfo.set(false);
    }

}