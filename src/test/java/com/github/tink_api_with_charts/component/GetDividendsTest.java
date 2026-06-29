package com.github.tink_api_with_charts.component;

import com.github.tink_api_with_charts.utils.NumberUtils;
import org.junit.jupiter.api.Test;
import ru.tinkoff.piapi.contract.v1.GetDividendsResponse;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;
import ru.ttech.piapi.core.connector.ServiceStubFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

class GetDividendsTest {

    public static final LocalDate DATE_FROM = LocalDate.of(2025, 1, 1);
    public static final LocalDate DATE_TO = LocalDate.of(2026, 6, 23);
    private static final String INSTRUMENT_UID_A = "87db07bc-0e02-4e29-90bb-05e8ef791d7b"; // ваш uid

    @Test
    void getDividends() {
        ConnectorConfiguration config = ConnectorConfiguration.loadPropertiesFromFile("config/application.yml");
        ServiceStubFactory serviceStubFactory = ServiceStubFactory.create(config);
        DividendsComponent dividendsComponent = new DividendsComponent(serviceStubFactory);
        GetDividendsResponse dividends = dividendsComponent.getDividends(INSTRUMENT_UID_A, DATE_FROM, DATE_TO);
        Map<LocalDate, BigDecimal> dateToDividend = new HashMap<>();
        dividends.getDividendsList().forEach(dividend -> {
            BigDecimal net = NumberUtils.moneyValueBigDecimal(dividend.getDividendNet());
            BigDecimal netMinusFee = net.multiply(BigDecimal.valueOf(0.87)).setScale(2, RoundingMode.DOWN);
            LocalDate paymentDate = NumberUtils.toLocalDate(dividend.getLastBuyDate());
            dateToDividend.put(paymentDate, netMinusFee);
        });
        System.out.println("dateToDividend = " + dateToDividend);
        System.out.println("dateToDividend.get(LocalDate.of(2025, 05, 15)) = " + dateToDividend.get(LocalDate.of(2025, 05, 15)));
    }

}