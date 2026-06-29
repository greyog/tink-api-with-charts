package com.github.tink_api_with_charts.component;

import com.github.tink_api_with_charts.cinfiguration.BalancerProperties;
import com.github.tink_api_with_charts.service.BalancerStateService;
import com.github.tink_api_with_charts.utils.NumberUtils;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.PositionData;
import ru.tinkoff.piapi.contract.v1.PositionsResponse;
import ru.tinkoff.piapi.contract.v1.PositionsSecurities;
import ru.tinkoff.piapi.contract.v1.PositionsStreamRequest;
import ru.tinkoff.piapi.contract.v1.PositionsStreamResponse;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;
import ru.ttech.piapi.core.connector.streaming.StreamServiceStubFactory;
import ru.ttech.piapi.core.impl.operations.PositionsStreamWrapperConfiguration;

import java.math.BigDecimal;
import java.util.concurrent.ScheduledExecutorService;

@Component
public class BalancerPositionsMonitor {

    public static final int INITIAL_SANDBOX_BALANCE = 1_000_000;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BalancerPositionsMonitor.class);

    private final BalancerProperties properties;
    private final ConnectorConfiguration configuration;
    //    private final AsyncStubWrapper<OperationsStreamServiceGrpc.OperationsStreamServiceFutureStub> operationsService;
    private final BalancerStateService balancerStateService;

    private String tradingAccountId;

    public BalancerPositionsMonitor(
            BalancerProperties properties,
            ConnectorConfiguration configuration,
            StreamServiceStubFactory streamServiceStubFactory,
            ScheduledExecutorService scheduledExecutorService,
            BalancerStateService balancerStateService
    ) {
        this.properties = properties;
        this.configuration = configuration;
        this.balancerStateService = balancerStateService;
        var wrapper = streamServiceStubFactory.newResilienceServerSideStream(
                PositionsStreamWrapperConfiguration.builder(scheduledExecutorService)
                        .addOnConnectListener(() -> log.info("PositionsStreamWrapper Connect"))
                        .addOnResponseListener(this::handlePositionsResponse)
                        .build());
        PositionsStreamRequest request = PositionsStreamRequest.newBuilder()
                .addAccounts(properties.getAccountId())
                .setWithInitialPositions(true)
                .build();
        wrapper.subscribe(request);
    }

    @SneakyThrows
    private void handlePositionsResponse(PositionsStreamResponse positionsResponse) {
        log.info("handlePositionsResponse: {}", positionsResponse);
        switch (positionsResponse.getPayloadCase()) {
            case INITIAL_POSITIONS -> handleInitialPositions(positionsResponse.getInitialPositions());
            case POSITION -> handlePositionData(positionsResponse.getPosition());
            default -> log.warn("Unknown PayloadCase: {}. Response : {}", positionsResponse.getPayloadCase(), positionsResponse);
        }
//        var initialPositions = positionsResponse.getInitialPositions();
//        handleInitialPositions(initialPositions);
//        PositionData positionData = positionsResponse.getPosition();
//        handlePositionData(positionData);

    }

    @SneakyThrows
    private void handlePositionData(PositionData positionData) {
        log.info("handlePositionData: {}", positionData); // todo
    }

    @SneakyThrows
    private void handleInitialPositions(PositionsResponse initialPositions) {
        log.info("handleInitialPositions: {}", initialPositions);
        MoneyValue rubMoneyValue = initialPositions.getMoneyList().stream()
                .filter(moneyValue -> "rub".equals(moneyValue.getCurrency()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Currency 'rub' not found between positions"));
        BigDecimal rubCash = NumberUtils.moneyValueBigDecimal(rubMoneyValue);
        balancerStateService.updateCashValue(rubCash);

        long shareQty = initialPositions.getSecuritiesList().stream()
                .filter(positionsSecurities -> positionsSecurities.getInstrumentUid().equals(properties.getShareUid()))
                .findFirst()
                .map(PositionsSecurities::getBalance)
                .orElse(0L);
        balancerStateService.updateShareQty(shareQty);

        long cashEtfQty = initialPositions.getSecuritiesList().stream()
                .filter(positionsSecurities -> positionsSecurities.getInstrumentUid().equals(properties.getCashEtfUid()))
                .findFirst()
                .map(PositionsSecurities::getBalance)
                .orElse(0L);
        balancerStateService.updateCashEtfQty(cashEtfQty);
    }

//    public static BalancerOperationsMonitor getInstance(ConnectorConfiguration config) {
//        ServiceStubFactory ssf = ServiceStubFactory.create(config);
//        return new BalancerOperationsMonitor(null, config, ssf, null);
//    }
//
//    @PostConstruct
//    public void init() {
//        PositionsResponse positions = getPositions(properties.getAccountId());
//        MoneyValue rubMoneyValue = positions.getMoneyList().stream()
//                .filter(moneyValue -> "rub".equals(moneyValue.getCurrency()))
//                .findFirst()
//                .orElseThrow(() -> new IllegalStateException("Currency 'rub' not found between positions"));
//        BigDecimal rubCash = NumberUtils.moneyValueBigDecimal(rubMoneyValue);
//        balancerStateService.updateCashValue(rubCash);
//
//        long shareQty = positions.getSecuritiesList().stream()
//                .filter(positionsSecurities -> positionsSecurities.getInstrumentUid().equals(properties.getShareUid()))
//                .findFirst()
//                .map(PositionsSecurities::getBalance)
//                .orElse(0L);
//        balancerStateService.updateShareQty(shareQty);
//
//        long cashEtfQty = positions.getSecuritiesList().stream()
//                .filter(positionsSecurities -> positionsSecurities.getInstrumentUid().equals(properties.getCashEtfUid()))
//                .findFirst()
//                .map(PositionsSecurities::getBalance)
//                .orElse(0L);
//        balancerStateService.updateCashEtfQty(cashEtfQty);
//
//        if (configuration.isSandboxEnabled()) {
//            GetAccountsResponse sandboxAccounts = sandboxService.getStub().getSandboxAccounts(
//                    GetAccountsRequest.newBuilder()
//                            .setStatus(AccountStatus.ACCOUNT_STATUS_OPEN)
//                            .build());
//            if (sandboxAccounts.getAccountsCount() == 0) {
//                OpenSandboxAccountResponse openSandboxAccountResponse = sandboxService.getStub().openSandboxAccount(
//                        OpenSandboxAccountRequest.newBuilder()
//                                .build());
//                tradingAccountId = openSandboxAccountResponse.getAccountId();
//            } else {
//                tradingAccountId = sandboxAccounts.getAccounts(0).getId();
//            }
//            payInSandbox();
//        }
//    }
//
//    private void payInSandbox() {
//        var balanceRequest = WithdrawLimitsRequest.newBuilder().setAccountId(tradingAccountId).build();
//        var balanceResponse = sandboxService.callSyncMethod(stub -> stub.getSandboxWithdrawLimits(balanceRequest));
//        var balance = balanceResponse.getMoneyList().stream().filter(moneyValue -> moneyValue.getCurrency().equals("rub"))
//                .findFirst()
//                .map(NumberMapper::moneyValueToBigDecimal)
//                .orElse(BigDecimal.ZERO);
//        var configBalance = BigDecimal.valueOf(INITIAL_SANDBOX_BALANCE);
//        log.info("Баланс: {} (настройка: {})", balance, configBalance);
//        if (balance.compareTo(BigDecimal.valueOf(INITIAL_SANDBOX_BALANCE)) < 0) {
//            var amount = configBalance.subtract(balance);
//            var payInRequest = SandboxPayInRequest.newBuilder()
//                    .setAccountId(tradingAccountId)
//                    .setAmount(NumberMapper.bigDecimalToMoneyValue(amount, "rub"))
//                    .build();
//            sandboxService.callSyncMethod(stub -> stub.sandboxPayIn(payInRequest));
//            log.info("Баланс песочницы пополнен на сумму: {} руб.", amount);
//        }
//    }
//
//    public GetAccountsResponse getAccounts() {
//        var accountsRequest = GetAccountsRequest.newBuilder()
//                .setStatus(AccountStatus.ACCOUNT_STATUS_ALL)
//                .build();
//        return userService.callSyncMethod(stub -> stub.getAccounts(accountsRequest));
//    }
//
//    public GetAccountValuesResponse getAccountValues(String accountId) {
//        var request = GetAccountValuesRequest.newBuilder()
//                .addAccounts(accountId)
//                .build();
//        return userService.callSyncMethod(stub -> stub.getAccountValues(request));
//    }
//
//    public PositionsResponse getPositions(String accountId) {
//        var request = PositionsRequest.newBuilder()
//                .setAccountId(accountId)
//                .build();
//        return operationsService.callSyncMethod(stub -> stub.getPositions(request));
//    }
//
//    public PortfolioResponse getPortfolio(String accountId) {
//        var request = PortfolioRequest.newBuilder()
//                .setAccountId(accountId)
//                .build();
//        return operationsService.callSyncMethod(stub -> stub.getPortfolio(request));
//    }
}