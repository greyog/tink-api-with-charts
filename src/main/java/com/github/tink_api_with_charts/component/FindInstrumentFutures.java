package com.github.tink_api_with_charts.component;

import jakarta.annotation.PostConstruct;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.tinkoff.piapi.contract.v1.AssetType;
import ru.tinkoff.piapi.contract.v1.Future;
import ru.tinkoff.piapi.contract.v1.FuturesResponse;
import ru.tinkoff.piapi.contract.v1.InstrumentsRequest;
import ru.tinkoff.piapi.contract.v1.InstrumentsServiceGrpc;
import ru.tinkoff.piapi.contract.v1.SecurityTradingStatus;
import ru.tinkoff.piapi.contract.v1.Share;
import ru.tinkoff.piapi.contract.v1.SharesResponse;
import ru.ttech.piapi.core.connector.ServiceStubFactory;
import ru.ttech.piapi.core.connector.SyncStubWrapper;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

@Slf4j
@Component
public class FindInstrumentFutures {

    private static final String OUTPUT_DIR = "output";
    private static final String FILE_NAME = "tradable_futures.csv";
    private final SyncStubWrapper<InstrumentsServiceGrpc.InstrumentsServiceBlockingStub> instrumentsService;

    public FindInstrumentFutures(
            ServiceStubFactory serviceStubFactory
    ) {
        this.instrumentsService = serviceStubFactory.newSyncService(InstrumentsServiceGrpc::newBlockingStub);
    }

//    @PostConstruct
    public void init() {
        FuturesResponse futures = instrumentsService.getStub().futures(InstrumentsRequest.newBuilder().build());
//        printTradableApiInstruments(futures);
        exportTradableInstrumentsToCsv(futures);
    }

    public void printTradableApiInstruments(FuturesResponse response) {
        response.getInstrumentsList().stream()
                .filter(Future::getApiTradeAvailableFlag)
                .filter(future -> SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING.equals(future.getTradingStatus()))
                .filter(future -> "TYPE_SECURITY".equals(future.getAssetType()))
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
                ));
    }

    @SneakyThrows
    public void exportTradableInstrumentsToCsv(FuturesResponse response) {
        // Создать папку, если её нет
        Path outputDir = Paths.get(OUTPUT_DIR);
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
            System.out.println("Создана директория: " + outputDir.toAbsolutePath());
        }

        // Путь к файлу
        Path csvFilePath = outputDir.resolve(FILE_NAME);

        // Запись в файл
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvFilePath.toFile()))) {
            // Заголовок
            writer.write("asset,\t ticker,\t FIGI,\t UID");
            writer.newLine();

            // Данные
            response.getInstrumentsList().stream()
                    .filter(Future::getApiTradeAvailableFlag)
                    .filter(future -> SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING.equals(future.getTradingStatus()))
                    .filter(future -> "TYPE_SECURITY".equals(future.getAssetType()))
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