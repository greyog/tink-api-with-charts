package com.github.tink_api_with_charts.component;

import com.github.tink_api_with_charts.cinfiguration.TradingProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.tinkoff.piapi.contract.v1.Account;
import ru.tinkoff.piapi.contract.v1.AccountStatus;
import ru.tinkoff.piapi.contract.v1.AccountType;
import ru.tinkoff.piapi.contract.v1.GetAccountsRequest;
import ru.tinkoff.piapi.contract.v1.GetAccountsResponse;
import ru.tinkoff.piapi.contract.v1.InstrumentsServiceGrpc;
import ru.tinkoff.piapi.contract.v1.OpenSandboxAccountRequest;
import ru.tinkoff.piapi.contract.v1.OpenSandboxAccountResponse;
import ru.tinkoff.piapi.contract.v1.Order;
import ru.tinkoff.piapi.contract.v1.OrderBook;
import ru.tinkoff.piapi.contract.v1.OrdersServiceGrpc;
import ru.tinkoff.piapi.contract.v1.Quotation;
import ru.tinkoff.piapi.contract.v1.SandboxPayInRequest;
import ru.tinkoff.piapi.contract.v1.SandboxServiceGrpc;
import ru.tinkoff.piapi.contract.v1.UsersServiceGrpc;
import ru.tinkoff.piapi.contract.v1.WithdrawLimitsRequest;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;
import ru.ttech.piapi.core.connector.ServiceStubFactory;
import ru.ttech.piapi.core.connector.SyncStubWrapper;
import ru.ttech.piapi.core.connector.resilience.ResilienceConfiguration;
import ru.ttech.piapi.core.connector.resilience.ResilienceSyncStubWrapper;
import ru.ttech.piapi.core.helpers.NumberMapper;
import ru.ttech.piapi.core.impl.marketdata.MarketDataStreamManager;
import ru.ttech.piapi.core.impl.marketdata.subscription.Instrument;
import ru.ttech.piapi.springboot.configuration.InvestAutoConfiguration;

import java.math.BigDecimal;
import java.util.Set;

@Slf4j
@Component
public class FindInstrument {

    private final TradingProperties properties;
    private final ConnectorConfiguration configuration;
    private final SyncStubWrapper<UsersServiceGrpc.UsersServiceBlockingStub> userService;
    private final SyncStubWrapper<InstrumentsServiceGrpc.InstrumentsServiceBlockingStub> instrumentsService;
    private final SyncStubWrapper<OrdersServiceGrpc.OrdersServiceBlockingStub> ordersService;
    private final SyncStubWrapper<SandboxServiceGrpc.SandboxServiceBlockingStub> sandboxService;
    private String tradingAccountId;

    public FindInstrument(
            TradingProperties properties,
            ConnectorConfiguration configuration,
            ServiceStubFactory serviceStubFactory
    ) {
        this.properties = properties;
        this.configuration = configuration;
        this.userService = serviceStubFactory.newSyncService(UsersServiceGrpc::newBlockingStub);
        this.instrumentsService = serviceStubFactory.newSyncService(InstrumentsServiceGrpc::newBlockingStub);
        this.ordersService = serviceStubFactory.newSyncService(OrdersServiceGrpc::newBlockingStub);
        this.sandboxService = serviceStubFactory.newSyncService(SandboxServiceGrpc::newBlockingStub);
    }

    @PostConstruct
    public void init() {
//        var accountsRequest = GetAccountsRequest.newBuilder()
//                .setStatus(AccountStatus.ACCOUNT_STATUS_ALL)
//                .build();
//        var accountsResponse = userService.callSyncMethod(stub -> stub.getAccounts(accountsRequest));
//        System.out.println("accountsResponse.getAccountsList() = " + accountsResponse.getAccountsList());
        //        tradingAccountId = accountsResponse.getAccountsList().stream()
//                .filter(acc -> acc.getType() == AccountType.ACCOUNT_TYPE_TINKOFF)
//                .findFirst()
//                .map(Account::getId)
//                .orElseThrow(() -> new IllegalStateException("Не найден открытый брокерский счет"));
//        log.info("Брокерский счет: {}", tradingAccountId);
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
        var configBalance = BigDecimal.valueOf(properties.getBalance());
        log.info("Баланс: {} (настройка: {})", balance, configBalance);
        if (balance.compareTo(BigDecimal.valueOf(properties.getBalance())) < 0) {
            var amount = configBalance.subtract(balance);
            var payInRequest = SandboxPayInRequest.newBuilder()
                    .setAccountId(tradingAccountId)
                    .setAmount(NumberMapper.bigDecimalToMoneyValue(amount, "rub"))
                    .build();
            sandboxService.callSyncMethod(stub -> stub.sandboxPayIn(payInRequest));
            log.info("Баланс песочницы пополнен на сумму: {} руб.", amount);
        }
    }
}