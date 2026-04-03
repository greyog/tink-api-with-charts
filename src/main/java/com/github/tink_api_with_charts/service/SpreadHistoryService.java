package com.github.tink_api_with_charts.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Slf4j
@Service
public class SpreadHistoryService {

    private static final String DB_FILE = "spread_history.db";

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
                spread_sell REAL,
                spread_buy REAL,
                spread_sell_qty INTEGER,
                spread_buy_qty INTEGER
            );
            """;

        try (Connection conn = DriverManager.getConnection(url)) {
            conn.createStatement().execute(sql);
            log.info("Таблица spread_history проверена/создана успешно");
        } catch (Exception e) {
            log.error("Ошибка при создании таблицы spread_history", e);
        }
    }

    public void saveSpreadData(BigDecimal spreadSell, BigDecimal spreadBuy, long spreadSellQty, long spreadBuyQty) {
        String url = "jdbc:sqlite:" + DB_FILE;
        String sql = "INSERT INTO spread_history(timestamp, spread_sell, spread_buy, spread_sell_qty, spread_buy_qty) VALUES (?, ?, ?, ?, ?)";

        try (var conn = DriverManager.getConnection(url); var pstmt = conn.prepareStatement(sql)) {

            pstmt.setTimestamp(1, Timestamp.from(LocalDateTime.now().toInstant(ZoneOffset.of("+03:00"))));
            pstmt.setBigDecimal(1, spreadSell);
            pstmt.setBigDecimal(2, spreadBuy);
            pstmt.setLong(3, spreadSellQty);
            pstmt.setLong(4, spreadBuyQty);
            pstmt.executeUpdate();

        } catch (Exception e) {
            log.error("Ошибка при сохранении спреда в БД", e);
        }
    }
}