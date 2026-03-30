package com.github.tink_api_with_charts.component;

import com.github.tink_api_with_charts.cinfiguration.TradingProperties;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.tinkoff.piapi.contract.v1.AccountStatus;
import ru.tinkoff.piapi.contract.v1.GetAccountsRequest;
import ru.tinkoff.piapi.contract.v1.GetAccountsResponse;
import ru.tinkoff.piapi.contract.v1.InstrumentRequest;
import ru.tinkoff.piapi.contract.v1.InstrumentsRequest;
import ru.tinkoff.piapi.contract.v1.InstrumentsServiceGrpc;
import ru.tinkoff.piapi.contract.v1.OpenSandboxAccountRequest;
import ru.tinkoff.piapi.contract.v1.OpenSandboxAccountResponse;
import ru.tinkoff.piapi.contract.v1.OrdersServiceGrpc;
import ru.tinkoff.piapi.contract.v1.SandboxPayInRequest;
import ru.tinkoff.piapi.contract.v1.SandboxServiceGrpc;
import ru.tinkoff.piapi.contract.v1.SecurityTradingStatus;
import ru.tinkoff.piapi.contract.v1.Share;
import ru.tinkoff.piapi.contract.v1.SharesResponse;
import ru.tinkoff.piapi.contract.v1.TradingStatus;
import ru.tinkoff.piapi.contract.v1.UsersServiceGrpc;
import ru.tinkoff.piapi.contract.v1.WithdrawLimitsRequest;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;
import ru.ttech.piapi.core.connector.ServiceStubFactory;
import ru.ttech.piapi.core.connector.SyncStubWrapper;
import ru.ttech.piapi.core.helpers.NumberMapper;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

@Slf4j
@Component
public class FindInstrument {

    private static final String OUTPUT_DIR = "output";
    private static final String FILE_NAME = "tradable_instruments.csv";
    private final TradingProperties properties;
    private final ConnectorConfiguration configuration;
    private final SyncStubWrapper<InstrumentsServiceGrpc.InstrumentsServiceBlockingStub> instrumentsService;


    public FindInstrument(
            TradingProperties properties,
            ConnectorConfiguration configuration,
            ServiceStubFactory serviceStubFactory
    ) {
        this.properties = properties;
        this.configuration = configuration;
        this.instrumentsService = serviceStubFactory.newSyncService(InstrumentsServiceGrpc::newBlockingStub);
    }

//    @PostConstruct
    public void init() {
        SharesResponse shares = instrumentsService.getStub().shares(InstrumentsRequest.newBuilder().build());
        exportTradableInstrumentsToCsv(shares);
    }

    public void printTradableApiInstruments(SharesResponse sharesResponse) {
        sharesResponse.getInstrumentsList().stream()
                .filter(Share::getApiTradeAvailableFlag)
                .filter(share -> SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING.equals(share.getTradingStatus()))
                .sorted(Comparator.comparing(Share::getTicker))
                .forEach(instrument -> System.out.printf(
                        "Ticker: %s,\t FIGI: %s,\t UID: %s%n",
                        instrument.getTicker(),
                        instrument.getFigi(),
                        instrument.getUid()
                ));
    }

    @SneakyThrows
    public void exportTradableInstrumentsToCsv(SharesResponse sharesResponse) {
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
            writer.write("Ticker,FIGI,UID");
            writer.newLine();

            // Данные
            sharesResponse.getInstrumentsList().stream()
                    .filter(Share::getApiTradeAvailableFlag)
                    .filter(share -> SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING.equals(share.getTradingStatus()))
                    .sorted(Comparator.comparing(Share::getTicker))
                    .forEach(instrument -> {
                        try {
                            writer.write(String.format("%s,%s,%s",
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