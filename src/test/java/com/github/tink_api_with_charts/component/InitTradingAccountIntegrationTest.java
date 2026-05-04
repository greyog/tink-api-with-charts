package com.github.tink_api_with_charts.component;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(InitTradingAccount.class) // чтобы можно было внедрить бин напрямую
public class InitTradingAccountIntegrationTest {

    @Autowired
    private InitTradingAccount initTradingAccount;

//    @Test
    void testInit_findsBrokerAccountInProductionMode() throws InterruptedException {
        // Act
//        findInstrument.init(); // вызов метода вручную (уже вызван через @PostConstruct)

        // Assert
        String accountId = initTradingAccount.getTradingAccountId();
        assertThat(accountId)
                .as("Должен быть найден активный брокерский счёт")
                .isNotBlank()
                .hasSizeBetween(30, 60); // типичная длина ID счёта в Tinkoff API
    }
}
