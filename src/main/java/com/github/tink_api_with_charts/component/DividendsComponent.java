package com.github.tink_api_with_charts.component;

import com.github.tink_api_with_charts.utils.NumberUtils;
import org.springframework.stereotype.Component;
import ru.tinkoff.piapi.contract.v1.GetDividendsRequest;
import ru.tinkoff.piapi.contract.v1.GetDividendsResponse;
import ru.tinkoff.piapi.contract.v1.InstrumentsServiceGrpc;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;
import ru.ttech.piapi.core.connector.ServiceStubFactory;
import ru.ttech.piapi.core.connector.SyncStubWrapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Component
public class DividendsComponent {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DividendsComponent.class);

    private final SyncStubWrapper<InstrumentsServiceGrpc.InstrumentsServiceBlockingStub> instrumentsService;

    public DividendsComponent(
            ServiceStubFactory serviceStubFactory
    ) {
        this.instrumentsService = serviceStubFactory.newSyncService(InstrumentsServiceGrpc::newBlockingStub);
    }

    public static DividendsComponent getInstance(ConnectorConfiguration config) {
        ServiceStubFactory serviceStubFactory = ServiceStubFactory.create(config);
        return new DividendsComponent(serviceStubFactory);
    }

    public GetDividendsResponse getDividends(String instrumentUid, LocalDate from, LocalDate to) {
        return instrumentsService.getStub().getDividends(GetDividendsRequest.newBuilder()
                .setInstrumentId(instrumentUid)
                .setFrom(NumberUtils.toTimestamp(from))
                .setTo(NumberUtils.toTimestamp(to))
                .build());
    }

    public Map<LocalDate, Double> getDateToDividendsMap(String instrumentUid, LocalDate from, LocalDate to) {
        GetDividendsResponse dividends = getDividends(instrumentUid, from, to);
        Map<LocalDate, Double> dateToDividend = new HashMap<>();
        dividends.getDividendsList().forEach(dividend -> {
            BigDecimal net = NumberUtils.moneyValueBigDecimal(dividend.getDividendNet());
            BigDecimal netMinusFee = net.multiply(BigDecimal.valueOf(0.87)).setScale(2, RoundingMode.DOWN);
            LocalDate paymentDate = NumberUtils.toLocalDate(dividend.getLastBuyDate());
            dateToDividend.put(paymentDate, netMinusFee.doubleValue());
        });
        return dateToDividend;
    }

}
