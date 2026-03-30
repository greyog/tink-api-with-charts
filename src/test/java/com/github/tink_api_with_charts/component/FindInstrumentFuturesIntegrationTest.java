package com.github.tink_api_with_charts.component;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(FindInstrumentFutures.class) // чтобы можно было внедрить бин напрямую
class FindInstrumentFuturesIntegrationTest {

    @Autowired
    private FindInstrumentFutures findInstrument;

    @Test
    void test() {

    }
}