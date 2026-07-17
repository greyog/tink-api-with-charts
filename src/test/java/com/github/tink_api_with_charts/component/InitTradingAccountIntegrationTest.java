package com.github.tink_api_with_charts.component;

import com.github.tink_api_with_charts.cinfiguration.TradingProperties;
import org.junit.jupiter.api.Test;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;
import ru.ttech.piapi.core.connector.ServiceStubFactory;

import static org.assertj.core.api.Assertions.assertThat;

public class InitTradingAccountIntegrationTest {

    private InitSandboxTradingAccount initTradingAccount;

    @Test
    void testInit_findsBrokerAccountInProductionMode() throws InterruptedException {

        var props = new TradingProperties();
        props.setSandboxInitialBalance(1_000_000);
        var configuration = ConnectorConfiguration.loadPropertiesFromFile("config/application.yml");
        ServiceStubFactory ssf = ServiceStubFactory.create(configuration);
        initTradingAccount = new InitSandboxTradingAccount(props, configuration, ssf);
        initTradingAccount.init();
        // Assert
        String accountId = initTradingAccount.getTradingAccountId();
        assertThat(accountId)
                .as("Должен быть найден активный брокерский счёт")
                .isNotBlank()
                .hasSizeBetween(30, 60); // типичная длина ID счёта в Tinkoff API
    }
}
