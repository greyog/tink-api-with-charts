package com.github.tink_api_with_charts.component;

import com.github.tink_api_with_charts.cinfiguration.BalancerProperties;
import com.github.tink_api_with_charts.event.StateCleanedEvent;
import com.github.tink_api_with_charts.service.BalancerStateService;
import jakarta.annotation.PostConstruct;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import ru.tinkoff.piapi.contract.v1.GetLastPricesRequest;
import ru.tinkoff.piapi.contract.v1.GetLastPricesResponse;
import ru.tinkoff.piapi.contract.v1.LastPrice;
import ru.tinkoff.piapi.contract.v1.MarketDataServiceGrpc;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;
import ru.ttech.piapi.core.connector.ServiceStubFactory;
import ru.ttech.piapi.core.connector.SyncStubWrapper;
import ru.ttech.piapi.core.helpers.NumberMapper;

@Component
public class BalancerMarketDataComponent {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BalancerMarketDataComponent.class);

    private final BalancerProperties properties;
    private final ConnectorConfiguration configuration;
    private final SyncStubWrapper<MarketDataServiceGrpc.MarketDataServiceBlockingStub> marketDataService;
    private final BalancerStateService balancerStateService;
    private final ApplicationEventPublisher eventPublisher;

    private String tradingAccountId;

    public BalancerMarketDataComponent(
            BalancerProperties properties,
            ConnectorConfiguration configuration,
            ServiceStubFactory serviceStubFactory,
            BalancerStateService balancerStateService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.properties = properties;
        this.configuration = configuration;
        this.marketDataService = serviceStubFactory.newSyncService(MarketDataServiceGrpc::newBlockingStub);
        this.balancerStateService = balancerStateService;
        this.eventPublisher = eventPublisher;
    }

    public static BalancerMarketDataComponent getInstance(ConnectorConfiguration config) {
        ServiceStubFactory ssf = ServiceStubFactory.create(config);
        return new BalancerMarketDataComponent(null, config, ssf, null, null);
    }

    @PostConstruct
    public void initCashEtfLastPrice() {
        GetLastPricesRequest request = GetLastPricesRequest.newBuilder()
                .addInstrumentId(properties.getCashEtfUid())
                .build();
        GetLastPricesResponse response = marketDataService.callSyncMethod(stub -> stub.getLastPrices(request));
        response.getLastPricesList().stream()
                .filter(lastPrice -> lastPrice.getInstrumentUid().equals(properties.getCashEtfUid()))
                .map(LastPrice::getPrice)
                .map(NumberMapper::quotationToBigDecimal)
                .forEach(bigDecimal -> balancerStateService.updateCashEtfPrice(bigDecimal, bigDecimal));
    }

    @Async
    @EventListener
    public void onStateCleaned(StateCleanedEvent event) {
        initCashEtfLastPrice();
    }

}