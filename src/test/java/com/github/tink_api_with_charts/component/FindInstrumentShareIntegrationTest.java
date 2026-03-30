package com.github.tink_api_with_charts.component;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(FindInstrumentShare.class) // чтобы можно было внедрить бин напрямую
class FindInstrumentShareIntegrationTest {

    @Autowired
    private FindInstrumentShare findInstrumentShare;

    @Test
    void test() {

    }
}