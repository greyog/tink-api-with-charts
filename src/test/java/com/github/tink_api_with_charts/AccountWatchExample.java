package com.github.tink_api_with_charts;

import com.github.tink_api_with_charts.component.TradingAccountComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;

public class AccountWatchExample {
    private static final Logger log = LoggerFactory.getLogger(AccountWatchExample.class);

    public static void main(String[] args) {
        var configuration = ConnectorConfiguration.loadPropertiesFromFile("config/application.yml");
        TradingAccountComponent tradingAccountComponent = TradingAccountComponent.getInstance(configuration);
        log.info("{}", tradingAccountComponent.getPortfolio(""));
    }

}