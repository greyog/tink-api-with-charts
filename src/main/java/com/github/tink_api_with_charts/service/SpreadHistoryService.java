package com.github.tink_api_with_charts.service;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class SpreadHistoryService {

    private static final String TABLE_NAME = "spread_history_v2";

    public static final String INSERT_SQL = "INSERT INTO %s(pair_name, timestamp, share_bid, share_ask, future_bid, future_ask, future_lot) VALUES (?, ?, ?, ?, ?, ?, ?)"
            .formatted(TABLE_NAME);
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SpreadHistoryService.class);

    private static final String DB_FILE = "spread_history.db";
    public static final String URL = "jdbc:sqlite:" + DB_FILE;

    @PostConstruct
    public void initDatabase() {
        createSpreadHistoryTable();
    }

    private void createSpreadHistoryTable() {
        String url = "jdbc:sqlite:" + DB_FILE;
        String sql = """
            CREATE TABLE IF NOT EXISTS %s (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pair_name TEXT NOT NULL,
                timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                share_bid REAL,
                share_ask REAL,
                future_bid REAL,
                future_ask REAL,
                future_lot INTEGER DEFAULT 1
            );
            CREATE INDEX IF NOT EXISTS idx_pair_name ON %s(pair_name);
            CREATE INDEX IF NOT EXISTS idx_timestamp ON %s(timestamp);
            """.formatted(TABLE_NAME, TABLE_NAME, TABLE_NAME);

        try (Connection conn = DriverManager.getConnection(url)) {
            conn.createStatement().execute(sql);
            log.info("Таблица spread_history проверена/создана успешно");
        } catch (Exception e) {
            log.error("Ошибка при создании таблицы spread_history", e);
        }
    }

    /**
     * Сохранение данных спреда для одной пары (устаревший метод)
     */
    @Deprecated
    public void saveSpreadData(BigDecimal shareBid, BigDecimal shareAsk, BigDecimal futureBid, BigDecimal futureAsk) {
        saveSpreadData("default", shareBid, shareAsk, futureBid, futureAsk, 1);
    }

    /**
     * Сохранение данных спреда для указанной пары инструментов
     *
     * @param pairName   имя пары инструментов (например, "SBER-SBERF")
     * @param shareBid   лучшая цена покупки акции
     * @param shareAsk   лучшая цена продажи акции
     * @param futureBid  лучшая цена покупки фьючерса
     * @param futureAsk  лучшая цена продажи фьючерса
     * @param futureLot  размер лота фьючерса (количество акций в одном фьючерсе)
     */
    public void saveSpreadData(String pairName, BigDecimal shareBid, BigDecimal shareAsk, 
                               BigDecimal futureBid, BigDecimal futureAsk, int futureLot) {
        try (var conn = DriverManager.getConnection(URL); 
             PreparedStatement pstmt = conn.prepareStatement(INSERT_SQL)) {

            pstmt.setString(1, pairName);
            pstmt.setTimestamp(2, Timestamp.from(LocalDateTime.now().toInstant(ZoneOffset.of("+03:00"))));
            pstmt.setBigDecimal(3, shareBid);
            pstmt.setBigDecimal(4, shareAsk);
            pstmt.setBigDecimal(5, futureBid);
            pstmt.setBigDecimal(6, futureAsk);
            pstmt.setInt(7, futureLot);
            pstmt.executeUpdate();

        } catch (Exception e) {
            log.error("Ошибка при сохранении спреда в БД для пары {}", pairName, e);
        }
    }
}
