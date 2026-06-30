package com.github.tink_api_with_charts.component;

import com.github.tink_api_with_charts.cinfiguration.BalancerProperties;
import com.github.tink_api_with_charts.service.BalancerStateService;
import com.github.tink_api_with_charts.utils.NumberUtils;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.PositionData;
import ru.tinkoff.piapi.contract.v1.PositionsMoney;
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
//        log.info("handlePositionsResponse: {}", positionsResponse);
        switch (positionsResponse.getPayloadCase()) {
            case INITIAL_POSITIONS -> handleInitialPositions(positionsResponse.getInitialPositions());
            case POSITION -> handlePositionData(positionsResponse.getPosition());
            default -> log.warn("Unknown PayloadCase: {}. Response : {}", positionsResponse.getPayloadCase(), positionsResponse);
        }
    }

    @SneakyThrows
    private void handlePositionData(PositionData positionData) {
//        log.info("handlePositionData: {}", positionData);
        BigDecimal availableRubValue = positionData.getMoneyList().stream()
                .map(PositionsMoney::getAvailableValue)
                .filter(moneyValue -> "rub".equals(moneyValue.getCurrency()))
                .findFirst()
                .map(NumberUtils::moneyValueBigDecimal)
                .orElse(BigDecimal.ZERO);
        BigDecimal blockedRubValue = positionData.getMoneyList().stream()
                .map(PositionsMoney::getBlockedValue)
                .filter(moneyValue -> "rub".equals(moneyValue.getCurrency()))
                .findFirst()
                .map(NumberUtils::moneyValueBigDecimal)
                .orElse(BigDecimal.ZERO);
        BigDecimal totalRubValue = availableRubValue.add(blockedRubValue);
        balancerStateService.updateCashValue(totalRubValue);

        long shareQty = positionData.getSecuritiesList().stream()
                .filter(positionsSecurities -> positionsSecurities.getInstrumentUid().equals(properties.getShareUid()))
                .findFirst()
                .map(ps -> ps.getBalance() + ps.getBlocked())
                .orElse(0L);
        balancerStateService.updateShareQty(shareQty);

        long cashEtfQty = positionData.getSecuritiesList().stream()
                .filter(positionsSecurities -> positionsSecurities.getInstrumentUid().equals(properties.getCashEtfUid()))
                .findFirst()
                .map(ps -> ps.getBalance() + ps.getBlocked())
                .orElse(0L);
        balancerStateService.updateCashEtfQty(cashEtfQty);
    }

    @SneakyThrows
    private void handleInitialPositions(PositionsResponse initialPositions) {
//        log.info("handleInitialPositions: {}", initialPositions);
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

}