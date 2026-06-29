package com.github.tink_api_with_charts.utils;

import com.google.protobuf.Timestamp;
import lombok.experimental.UtilityClass;
import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.Quotation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@UtilityClass
public class NumberUtils {

    public BigDecimal moneyValueBigDecimal(MoneyValue q) {
        BigDecimal units = BigDecimal.valueOf(q.getUnits());
        BigDecimal nano = BigDecimal.valueOf(q.getNano(), 9);
        BigDecimal result = units.add(nano);
        return result.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal quotationToBigDecimal(Quotation q) {
        BigDecimal units = BigDecimal.valueOf(q.getUnits());
        BigDecimal nano = BigDecimal.valueOf(q.getNano(), 9);
        BigDecimal result = units.add(nano);
        return result.setScale(2, RoundingMode.HALF_UP);
    }

    public LocalDate toLocalDate(Timestamp timestamp) {
        if (timestamp == null || timestamp.getSeconds() == 0 && timestamp.getNanos() == 0) {
            return null;
        }
        Instant instant = Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    public Timestamp toTimestamp(LocalDate localDate) {
        // 1. Convert LocalDate to Instant at the start of the day in UTC
        Instant instant = localDate.atStartOfDay().toInstant(ZoneOffset.UTC);

        // 2. Build and return the Protocol Buffers Timestamp
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build(); // Returns Timestamp, which implements TimestampOrBuilder
    }

}
