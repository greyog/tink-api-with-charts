package com.github.tink_api_with_charts;

import com.github.tink_api_with_charts.component.BalancerAccountComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;

public class PositionsMonitorExample {
    private static final Logger log = LoggerFactory.getLogger(PositionsMonitorExample.class);

    public static void main(String[] args) {
        var configuration = ConnectorConfiguration.loadPropertiesFromFile("config/application.yml");
        BalancerAccountComponent tradingAccountComponent = BalancerAccountComponent.getInstance(configuration);
        log.info("{}", tradingAccountComponent.getPositions(""));
    }

}