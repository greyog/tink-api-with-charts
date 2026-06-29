package com.github.tink_api_with_charts.component;

import com.github.tink_api_with_charts.cinfiguration.BalancerProperties;
import com.github.tink_api_with_charts.service.BalancerStateService;
import com.github.tink_api_with_charts.utils.NumberUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import ru.tinkoff.piapi.contract.v1.AccountStatus;
import ru.tinkoff.piapi.contract.v1.GetAccountValuesRequest;
import ru.tinkoff.piapi.contract.v1.GetAccountValuesResponse;
import ru.tinkoff.piapi.contract.v1.GetAccountsRequest;
import ru.tinkoff.piapi.contract.v1.GetAccountsResponse;
import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.OpenSandboxAccountRequest;
import ru.tinkoff.piapi.contract.v1.OpenSandboxAccountResponse;
import ru.tinkoff.piapi.contract.v1.OperationsServiceGrpc;
import ru.tinkoff.piapi.contract.v1.PortfolioRequest;
import ru.tinkoff.piapi.contract.v1.PortfolioResponse;
import ru.tinkoff.piapi.contract.v1.PositionsRequest;
import ru.tinkoff.piapi.contract.v1.PositionsResponse;
import ru.tinkoff.piapi.contract.v1.PositionsSecurities;
import ru.tinkoff.piapi.contract.v1.SandboxPayInRequest;
import ru.tinkoff.piapi.contract.v1.SandboxServiceGrpc;
import ru.tinkoff.piapi.contract.v1.UsersServiceGrpc;
import ru.tinkoff.piapi.contract.v1.WithdrawLimitsRequest;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;
import ru.ttech.piapi.core.connector.ServiceStubFactory;
import ru.ttech.piapi.core.connector.SyncStubWrapper;
import ru.ttech.piapi.core.helpers.NumberMapper;

import java.math.BigDecimal;

@Component
public class BalancerAccountComponent {

    public static final int INITIAL_SANDBOX_BALANCE = 1_000_000;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BalancerAccountComponent.class);

    private final BalancerProperties properties;
    private final ConnectorConfiguration configuration;
    private final SyncStubWrapper<UsersServiceGrpc.UsersServiceBlockingStub> userService;
    private final SyncStubWrapper<SandboxServiceGrpc.SandboxServiceBlockingStub> sandboxService;
    private final SyncStubWrapper<OperationsServiceGrpc.OperationsServiceBlockingStub> operationsService;
    private final BalancerStateService balancerStateService;

    private String tradingAccountId;

    public BalancerAccountComponent(
            BalancerProperties properties,
            ConnectorConfiguration configuration,
            ServiceStubFactory serviceStubFactory,
            BalancerStateService balancerStateService
    ) {
        this.properties = properties;
        this.configuration = configuration;
        this.userService = serviceStubFactory.newSyncService(UsersServiceGrpc::newBlockingStub);
        this.sandboxService = serviceStubFactory.newSyncService(SandboxServiceGrpc::newBlockingStub);
        this.operationsService = serviceStubFactory.newSyncService(OperationsServiceGrpc::newBlockingStub);
        this.balancerStateService = balancerStateService;
    }

    public static BalancerAccountComponent getInstance(ConnectorConfiguration config) {
        ServiceStubFactory ssf = ServiceStubFactory.create(config);
        return new BalancerAccountComponent(null, config, ssf, null);
    }

    @PostConstruct
    public void init() {
        PositionsResponse positions = getPositions(properties.getAccountId());
        MoneyValue rubMoneyValue = positions.getMoneyList().stream()
                .filter(moneyValue -> "rub".equals(moneyValue.getCurrency()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Currency 'rub' not found between positions"));
        BigDecimal rubCash = NumberUtils.moneyValueBigDecimal(rubMoneyValue);
        balancerStateService.updateCashValue(rubCash);

        long shareQty = positions.getSecuritiesList().stream()
                .filter(positionsSecurities -> positionsSecurities.getInstrumentUid().equals(properties.getShareUid()))
                .findFirst()
                .map(PositionsSecurities::getBalance)
                .orElse(0L);
        balancerStateService.updateShareQty(shareQty);

        long cashEtfQty = positions.getSecuritiesList().stream()
                .filter(positionsSecurities -> positionsSecurities.getInstrumentUid().equals(properties.getCashEtfUid()))
                .findFirst()
                .map(PositionsSecurities::getBalance)
                .orElse(0L);
        balancerStateService.updateCashEtfQty(cashEtfQty);

        if (configuration.isSandboxEnabled()) {
            GetAccountsResponse sandboxAccounts = sandboxService.getStub().getSandboxAccounts(
                    GetAccountsRequest.newBuilder()
                            .setStatus(AccountStatus.ACCOUNT_STATUS_OPEN)
                            .build());
            if (sandboxAccounts.getAccountsCount() == 0) {
                OpenSandboxAccountResponse openSandboxAccountResponse = sandboxService.getStub().openSandboxAccount(
                        OpenSandboxAccountRequest.newBuilder()
                                .build());
                tradingAccountId = openSandboxAccountResponse.getAccountId();
            } else {
                tradingAccountId = sandboxAccounts.getAccounts(0).getId();
            }
            payInSandbox();
        }
    }

    private void payInSandbox() {
        var balanceRequest = WithdrawLimitsRequest.newBuilder().setAccountId(tradingAccountId).build();
        var balanceResponse = sandboxService.callSyncMethod(stub -> stub.getSandboxWithdrawLimits(balanceRequest));
        var balance = balanceResponse.getMoneyList().stream().filter(moneyValue -> moneyValue.getCurrency().equals("rub"))
                .findFirst()
                .map(NumberMapper::moneyValueToBigDecimal)
                .orElse(BigDecimal.ZERO);
        var configBalance = BigDecimal.valueOf(INITIAL_SANDBOX_BALANCE);
        log.info("Баланс: {} (настройка: {})", balance, configBalance);
        if (balance.compareTo(BigDecimal.valueOf(INITIAL_SANDBOX_BALANCE)) < 0) {
            var amount = configBalance.subtract(balance);
            var payInRequest = SandboxPayInRequest.newBuilder()
                    .setAccountId(tradingAccountId)
                    .setAmount(NumberMapper.bigDecimalToMoneyValue(amount, "rub"))
                    .build();
            sandboxService.callSyncMethod(stub -> stub.sandboxPayIn(payInRequest));
            log.info("Баланс песочницы пополнен на сумму: {} руб.", amount);
        }
    }

    public GetAccountsResponse getAccounts() {
        var accountsRequest = GetAccountsRequest.newBuilder()
                .setStatus(AccountStatus.ACCOUNT_STATUS_ALL)
                .build();
        return userService.callSyncMethod(stub -> stub.getAccounts(accountsRequest));
    }

    public GetAccountValuesResponse getAccountValues(String accountId) {
        var request = GetAccountValuesRequest.newBuilder()
                .addAccounts(accountId)
                .build();
        return userService.callSyncMethod(stub -> stub.getAccountValues(request));
    }

    public PositionsResponse getPositions(String accountId) {
        var request = PositionsRequest.newBuilder()
                .setAccountId(accountId)
                .build();
        return operationsService.callSyncMethod(stub -> stub.getPositions(request));
    }

    public PortfolioResponse getPortfolio(String accountId) {
        var request = PortfolioRequest.newBuilder()
                .setAccountId(accountId)
                .build();
        return operationsService.callSyncMethod(stub -> stub.getPortfolio(request));
    }
}