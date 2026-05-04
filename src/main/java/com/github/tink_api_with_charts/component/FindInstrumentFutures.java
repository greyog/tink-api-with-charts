package com.github.tink_api_with_charts.component;

import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import ru.tinkoff.piapi.contract.v1.Future;
import ru.tinkoff.piapi.contract.v1.FuturesResponse;
import ru.tinkoff.piapi.contract.v1.InstrumentsRequest;
import ru.tinkoff.piapi.contract.v1.InstrumentsServiceGrpc;
import ru.tinkoff.piapi.contract.v1.SecurityTradingStatus;
import ru.ttech.piapi.core.connector.ServiceStubFactory;
import ru.ttech.piapi.core.connector.SyncStubWrapper;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Collectors;

@Component
public class FindInstrumentFutures {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FindInstrumentFutures.class);

    private static final String OUTPUT_DIR = "output";
    private static final String FILE_NAME_TEMPLATE = "tradable_futures_%s.csv";
    private final SyncStubWrapper<InstrumentsServiceGrpc.InstrumentsServiceBlockingStub> instrumentsService;

    public FindInstrumentFutures(
            ServiceStubFactory serviceStubFactory
    ) {
        this.instrumentsService = serviceStubFactory.newSyncService(InstrumentsServiceGrpc::newBlockingStub);
    }

    public void printTradableApiInstruments() {
        FuturesResponse futures = instrumentsService.getStub().futures(InstrumentsRequest.newBuilder().build());
        futures.getInstrumentsList().stream()
                .filter(Future::getApiTradeAvailableFlag)
                .filter(future -> SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING.equals(future.getTradingStatus()))
                .filter(future -> "TYPE_".equals(future.getAssetType()))
                .sorted(Comparator.comparing(Future::getTicker))
//                .limit(1)
//                .forEach(instrument -> System.out.println(
//                        instrument
//                ));
                .forEach(instrument -> System.out.printf(
                        "Basic Asset: %s,\t Ticker: %s,\t\t FIGI: %s,\t UID: %s%n",
                        instrument.getBasicAsset(),
                        instrument.getTicker(),
                        instrument.getFigi(),
                        instrument.getUid()
                ))
        ;
    }


//    TYPE_COMMODITY
//TYPE_CURRENCY
//TYPE_INDEX
//TYPE_SECURITY

    public void printAssetTypes() {
        FuturesResponse futures = instrumentsService.getStub().futures(InstrumentsRequest.newBuilder().build());
        futures.getInstrumentsList().stream()
                .filter(Future::getApiTradeAvailableFlag)
                .filter(future -> SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING.equals(future.getTradingStatus()))
                .map(Future::getAssetType)
                .collect(Collectors.toSet())
                .stream().sorted()
                .forEach(System.out::println);
        ;
    }

    @SneakyThrows
    public void exportTradableInstrumentsToCsv() {
        String assetTypeToPrint = "TYPE_COMMODITY";
        FuturesResponse futures = instrumentsService.getStub().futures(InstrumentsRequest.newBuilder().build());
        // Создать папку, если её нет
        Path outputDir = Paths.get(OUTPUT_DIR);
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
            System.out.println("Создана директория: " + outputDir.toAbsolutePath());
        }

        // Путь к файлу
        Path csvFilePath = outputDir.resolve(FILE_NAME_TEMPLATE.formatted(assetTypeToPrint));

        // Запись в файл
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvFilePath.toFile()))) {
            // Заголовок
            writer.write("asset,\t ticker,\t FIGI,\t UID");
            writer.newLine();

            // Данные
            futures.getInstrumentsList().stream()
                    .filter(Future::getApiTradeAvailableFlag)
                    .filter(future -> SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING.equals(future.getTradingStatus()))
                    .filter(future -> assetTypeToPrint.equals(future.getAssetType()))
                    .sorted(Comparator.comparing(Future::getTicker))
                    .forEach(instrument -> {
                        try {
                            writer.write(String.format("%s,\t %s,\t %s,\t %s",
                                    instrument.getBasicAsset(),
                                    instrument.getTicker(),
                                    instrument.getFigi(),
                                    instrument.getUid()));
                            writer.newLine();
                        } catch (IOException e) {
                            throw new RuntimeException("Ошибка при записи строки", e);
                        }
                    });

            log.info("Файл сохранён: " + csvFilePath.toAbsolutePath());
        }

    }

}