package com.github.tink_api_with_charts.component;

import org.junit.jupiter.api.Test;
import ru.tinkoff.piapi.contract.v1.GetDividendsResponse;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;
import ru.ttech.piapi.core.connector.ServiceStubFactory;

import java.math.BigDecimal;
import java.time.LocalDate;

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
        dividends.getDividendsList().forEach(dividend -> {
            BigDecimal net = dividendsComponent.moneyValueBigDecimal(dividend.getDividendNet());
            LocalDate paymentDate = dividendsComponent.toLocalDate(dividend.getLastBuyDate());
            System.out.println("paymentDate = " + paymentDate + ", net = " + net);
        });
    }

}