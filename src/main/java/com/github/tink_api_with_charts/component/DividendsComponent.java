package com.github.tink_api_with_charts.component;

import com.google.protobuf.Timestamp;
import org.springframework.stereotype.Component;
import ru.tinkoff.piapi.contract.v1.GetDividendsRequest;
import ru.tinkoff.piapi.contract.v1.GetDividendsResponse;
import ru.tinkoff.piapi.contract.v1.InstrumentsServiceGrpc;
import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;
import ru.ttech.piapi.core.connector.ServiceStubFactory;
import ru.ttech.piapi.core.connector.SyncStubWrapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
                .setFrom(toTimestamp(from))
                .setTo(toTimestamp(to))
                .build());
    }

    public Map<LocalDate, Double> getDateToDividendsMap(String instrumentUid, LocalDate from, LocalDate to) {
        GetDividendsResponse dividends = getDividends(instrumentUid, from, to);
        Map<LocalDate, Double> dateToDividend = new HashMap<>();
        dividends.getDividendsList().forEach(dividend -> {
            BigDecimal net = this.moneyValueBigDecimal(dividend.getDividendNet());
            BigDecimal netMinusFee = net.multiply(BigDecimal.valueOf(0.87)).setScale(2, RoundingMode.DOWN);
            LocalDate paymentDate = this.toLocalDate(dividend.getLastBuyDate());
            dateToDividend.put(paymentDate, netMinusFee.doubleValue());
        });
        return dateToDividend;
    }

    private Timestamp toTimestamp(LocalDate localDate) {
        // 1. Convert LocalDate to Instant at the start of the day in UTC
        Instant instant = localDate.atStartOfDay().toInstant(ZoneOffset.UTC);

        // 2. Build and return the Protocol Buffers Timestamp
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build(); // Returns Timestamp, which implements TimestampOrBuilder
    }

    public LocalDate toLocalDate(Timestamp timestamp) {
        if (timestamp == null || timestamp.getSeconds() == 0 && timestamp.getNanos() == 0) {
            return null;
        }
        Instant instant = Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    public BigDecimal moneyValueBigDecimal(MoneyValue q) {
        BigDecimal units = BigDecimal.valueOf(q.getUnits());
        BigDecimal nano = BigDecimal.valueOf(q.getNano(), 9);
        BigDecimal result = units.add(nano);
        return result.setScale(2, RoundingMode.HALF_UP);
    }

}
