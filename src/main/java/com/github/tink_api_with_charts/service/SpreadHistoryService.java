package com.github.tink_api_with_charts.service;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class SpreadHistoryService {

    public static final String INSERT_SQL = "INSERT INTO spread_history(timestamp, share_bid, share_ask, future_bid, future_ask) VALUES (?, ?, ?, ?, ?)";
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
            CREATE TABLE IF NOT EXISTS spread_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                share_bid REAL,
                share_ask REAL,
                future_bid REAL,
                future_ask REAL
            );
            """;

        try (Connection conn = DriverManager.getConnection(url)) {
            conn.createStatement().execute(sql);
            log.info("Таблица spread_history проверена/создана успешно");
        } catch (Exception e) {
            log.error("Ошибка при создании таблицы spread_history", e);
        }
    }

    public void saveSpreadData(BigDecimal shareBid, BigDecimal shareAsk, BigDecimal futureBid, BigDecimal futureAsk) {
        try (var conn = DriverManager.getConnection(URL); var pstmt = conn.prepareStatement(INSERT_SQL)) {

            pstmt.setTimestamp(1, Timestamp.from(LocalDateTime.now().toInstant(ZoneOffset.of("+03:00"))));
            pstmt.setBigDecimal(2, shareBid);
            pstmt.setBigDecimal(3, shareAsk);
            pstmt.setBigDecimal(4, futureBid);
            pstmt.setBigDecimal(5, futureAsk);
            pstmt.executeUpdate();

        } catch (Exception e) {
            log.error("Ошибка при сохранении спреда в БД", e);
        }
    }
}