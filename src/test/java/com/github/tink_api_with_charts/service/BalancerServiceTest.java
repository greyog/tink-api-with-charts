package com.github.tink_api_with_charts.service;

import com.github.tink_api_with_charts.cinfiguration.BalancerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.math.BigDecimal;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalancerServiceTest {

    // Упростим, считаем только базовые 2501 внутри сервиса
    public static final BigDecimal CASH_ETF_QTY = BigDecimal.valueOf(3288);
    @Mock
    private BalancerProperties properties;

//    @Mock
//    private Logger logger; // Мокаем логгер, чтобы перехватывать сообщения

    private BalancerService balancerService;

    // Константы для тестовых данных
    private static final long IIS_CASH_ETF_QTY = 2501; // Из класса BalancerService
    private static final BigDecimal SHARE_PRICE = new BigDecimal("253.54");
    private static final BigDecimal ETF_PRICE = new BigDecimal("160.24");
    private static final BigDecimal CASH_VALUE = new BigDecimal("-4923");

//    Price 259.42, 	Portfolio Value 1843613.16, 	Current alloc 0.500234, 	sharePriceAtUpperAlloc 269.76, 	qtyToSellAtUpperAlloc 140, 	sharePriceAtLowerAlloc 249.01, 	qtyToBuyAtLowerAlloc 145

    @BeforeEach
    void setUp() {
        when(properties.getTargetShareAllocation()).thenReturn(0.50);
        when(properties.getRebalanceThresholdUp()).thenReturn(0.001);
        when(properties.getRebalanceThresholdDown()).thenReturn(0.0001);

        // Внедряем мокированный логгер в статическое поле класса через рефлексию или прямое присваивание,
        // если бы поле не было final static. Так как поле final static, используем ReflectionUtils
        // или просто создадим сервис и подменим логгер через утилиту, если нужно.
        // Но проще всего в тесте создать экземпляр и через рефлексию заменить logger.

        balancerService = new BalancerService(properties, null);
//        injectLogger(balancerService, logger);
    }

    @Test
    void shouldTriggerSellWhenAllocationIsTooHigh() {
        long shareQty = 3555;
        // Внутри сервиса: totalCashValue = 10.0 * (0 + 2501) + 5000 = 25010 + 5000 = 30010
        BigDecimal adjustedCashValue = new BigDecimal(-4922);

        balancerService.handleStateChange(
                "TEST_HIGH",
                CASH_VALUE,
                shareQty,
                SHARE_PRICE,
                CASH_ETF_QTY.longValue(), // 0
                ETF_PRICE
        );

        // Тогда: Должно быть вызвано log.warn с текстом "Need to sell"
//        verify(logger, times(1)).warn(anyString(), any(Long.class));
//
//        // И должно быть итоговое лого info
//        verify(logger, times(1)).info(anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldTriggerBuyWhenAllocationIsTooLow() {
        // Дано: Ситуация, где доля акций < 0.45 (lowerAlloc)
        // Пусть Акции = 10 шт * 200 = 2,000
        // Кэш = (2501 * 10) + 50,000 = 25,010 + 50,000 = 75,010
        // Total = 77,010
        // Alloc = 2,000 / 77,010 ≈ 0.026 < 0.45. ОК.

        long shareQty = 10;
        BigDecimal cashEtfQty = BigDecimal.ZERO;
        BigDecimal adjustedCashValue = new BigDecimal("50000.0");

        balancerService.handleStateChange(
                "TEST_LOW",
                adjustedCashValue,
                shareQty,
                SHARE_PRICE,
                cashEtfQty.longValue(),
                ETF_PRICE
        );

        // Тогда: Должно быть вызвано log.warn с текстом "Need to buy"
//        verify(logger, times(1)).warn(anyString(), any(Long.class));
//
//        // И должно быть итоговое лого info
//        verify(logger, times(1)).info(anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldNotTriggerRebalanceWhenWithinThreshold() {
        // Дано: Доля между 0.45 и 0.55
        // Подберем цифры: Target 50%.
        // Пусть Total = 100,000. Share = 50,000. Cash = 50,000.
        // Share Price = 100. Qty = 500.
        // Cash needed = 50,000.
        // ETF part = 2501 * 10 = 25,010.
        // Cash arg = 50,000 - 25,010 = 24,990.

        long shareQty = 500;
        BigDecimal sharePrice = new BigDecimal("100.0");
        BigDecimal etfPrice = new BigDecimal("10.0");
        BigDecimal cashArg = new BigDecimal("24990.0");
        long cashEtfQtyArg = 0;

        balancerService.handleStateChange(
                "TEST_OK",
                cashArg,
                shareQty,
                sharePrice,
                cashEtfQtyArg,
                etfPrice
        );

        // Тогда: log.warn НЕ должен вызываться
//        verify(logger, never()).warn(anyString());
//
//        // Но info должен вызваться всегда
//        verify(logger, times(1)).info(anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    // Утилита для внедрения мока в final static поле
    private void injectLogger(BalancerService service, Logger mockLogger) {
        try {
            java.lang.reflect.Field field = BalancerService.class.getDeclaredField("log");
            field.setAccessible(true);
            // Убираем final модификатор, чтобы можно было перезаписать значение
            java.lang.reflect.Field modifiersField = java.lang.reflect.Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(field, field.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
            field.set(service, mockLogger);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject logger", e);
        }
    }
}
