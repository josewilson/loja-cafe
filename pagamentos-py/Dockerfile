# Pagamentos simulado (Python 3 + Flask + Waitress). Lê pedidos-criados do Kafka e publica o resultado.
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && useradd --system --uid 10001 --no-create-home loja
COPY app.py .
USER loja
# Dentro do contêiner escuta em todas as interfaces; a porta não é publicada para fora (só a rede interna do Docker alcança).
ENV HOST=0.0.0.0 PORTA=5001 PYTHONUNBUFFERED=1
EXPOSE 5001
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
    CMD python -c "import sys, urllib.request; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:5001/saude', timeout=3).status == 200 else 1)"
CMD ["python", "app.py"]
