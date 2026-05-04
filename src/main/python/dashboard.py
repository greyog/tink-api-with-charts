import streamlit as st
import sqlite3
import pandas as pd
import plotly.express as px
from datetime import datetime, date
import time
import os

st.set_page_config(page_title="Order Book Monitor", layout="wide")

# Относительный путь к БД (на 4 уровня выше скрипта)
DB_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../../spread_history.db'))
print(f"Путь к БД: {DB_PATH}")

# Автообновление каждые 5 секунд
if 'last_update' not in st.session_state:
    st.session_state.last_update = time.time()

if time.time() - st.session_state.last_update > 5:
    st.session_state.last_update = time.time()
    st.rerun()

# Подключение к БД
@st.cache_data(ttl=10)  # Кэширование на 10 секунд
def load_data():
    try:
        conn = sqlite3.connect(DB_PATH)

        df = pd.read_sql_query("""
            SELECT 
                id,
                timestamp,
                share_bid,
                share_ask,
                future_bid,
                future_ask
            FROM spread_history 
            WHERE DATE(datetime(timestamp/1000, 'unixepoch', 'localtime')) = DATE('now')
            ORDER BY id
        """, conn)
        conn.close()
        # Конвертация миллисекунд в datetime
        df['timestamp'] = pd.to_datetime(df['timestamp'], unit='ms')
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
        # Основной график с 4 линиями
        fig_spread = px.line(df, x='timestamp', 
                             y=['share_bid', 'share_ask', 'future_bid', 'future_ask'],
                             title=f"Динамика котировок - {today.strftime('%d.%m.%Y')}",
                             labels={'value': 'Значение', 'timestamp': 'Время'},
                             color_discrete_sequence=px.colors.qualitative.Set1)

        # Настройка графика для прокрутки
        fig_spread.update_layout(
            xaxis=dict(
                rangeslider=dict(visible=True),  # Полоса прокрутки
                type='date',
                fixedrange=False  # Разрешить зум
            ),
            yaxis=dict(
                fixedrange=False,
                title='Значение'
            ),
            hovermode='x unified',
            height=800,
            width=None,
            autosize=True
        )

        # Добавить сетку и улучшить вид
        fig_spread.update_xaxes(showgrid=True, gridwidth=1, gridcolor='lightgray')
        fig_spread.update_yaxes(showgrid=True, gridwidth=1, gridcolor='lightgray')

        # Отображение графика
        st.plotly_chart(fig_spread, use_container_width=True)

    else:
        st.warning("Нет данных за сегодня")
else:
    st.warning("Нет данных для отображения. Убедитесь, что файл spread_history.db существует.")
