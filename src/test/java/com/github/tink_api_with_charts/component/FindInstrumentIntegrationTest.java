package com.github.tink_api_with_charts.component;

import com.github.tink_api_with_charts.cinfiguration.TradingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(FindInstrument.class) // чтобы можно было внедрить бин напрямую
public class FindInstrumentIntegrationTest {

    @Autowired
    private FindInstrument findInstrument;

    @Test
    void testInit_findsBrokerAccountInProductionMode() throws InterruptedException {
        // Act
//        findInstrument.init(); // вызов метода вручную (уже вызван через @PostConstruct)

        // Assert
        String accountId = findInstrument.getTradingAccountId();
        assertThat(accountId)
                .as("Должен быть найден активный брокерский счёт")
                .isNotBlank()
                .hasSizeBetween(30, 60); // типичная длина ID счёта в Tinkoff API
    }
}
