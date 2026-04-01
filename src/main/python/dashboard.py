import streamlit as st
import sqlite3
import pandas as pd
import plotly.express as px
from datetime import datetime, date

st.set_page_config(page_title="Order Book Monitor", layout="wide")

DB_PATH ='spread_history.db'

# Подключение к БД
@st.cache_data(ttl=30)  # Кэширование на 30 секунд
def load_data():
    try:
        conn = sqlite3.connect(DB_PATH)

        df = pd.read_sql_query("""
            SELECT * 
            FROM spread_history 
            WHERE DATE(timestamp) = DATE('now')
            ORDER BY id
        """, conn)
        conn.close()
        df['timestamp'] = pd.to_datetime(df['timestamp'])
        return df
    except Exception as e:
        st.error(f"Ошибка подключения к БД: {e}")
        return pd.DataFrame()

# Загрузка данных
df = load_data()

if not df.empty:
    # st.title("📊 Order Book Monitor - Сегодня")

    # Фильтр по сегодняшнему дню
    today = date.today()
    # df_today = df[df['timestamp'].dt.date == today].sort_values('timestamp')

    if not df.empty:
        # Основной график спреда с логарифмической шкалой
        fig_spread = px.line(df, x='timestamp', y=['spread_sell', 'spread_buy'],
                             title=f"Динамика спреда - {today.strftime('%d.%m.%Y')} (логарифмическая шкала)",
                             labels={'value': 'Спред (лог)', 'timestamp': 'Время'})

        # Настройка графика для прокрутки и логарифмической шкалы
        fig_spread.update_layout(
            xaxis=dict(
                rangeslider=dict(visible=True),  # Полоса прокрутки
                type='date',
                fixedrange=False  # Разрешить зум
            ),
            yaxis=dict(
                fixedrange=False,
                # type='log',  # Логарифмическая шкала!
                title='Спред (лог)'
            ),
            hovermode='x unified',
            # УВЕЛИЧЕННЫЙ РАЗМЕР ГРАФИКА
            height=800,  # Высота в пикселях
            width=None,
            autosize=True
        )

        # Добавить сетку и улучшить вид
        fig_spread.update_xaxes(showgrid=True, gridwidth=1, gridcolor='lightgray')
        fig_spread.update_yaxes(showgrid=True, gridwidth=1, gridcolor='lightgray')

        # Отображение графика БЕЗ устаревших параметров
        st.plotly_chart(fig_spread, use_container_width=True)

    else:
        st.warning("Нет данных за сегодня")
else:
    st.warning("Нет данных для отображения. Убедитесь, что файл spread_history.db существует.")