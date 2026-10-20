# Imagem de backup: pg_dump/pg_restore (mesma versão do servidor, Postgres 16) + age (criptografia) + rclone (cópia para fora).
FROM postgres:16
RUN apt-get update \
    && apt-get install -y --no-install-recommends rclone age curl ca-certificates tzdata \
    && rm -rf /var/lib/apt/lists/*
COPY backup.sh retencao.sh restaurar.sh agendador.sh /usr/local/bin/
# sed: se os scripts foram copiados de um Windows com fim de linha CRLF, o bash quebraria ("\r: comando não encontrado").
RUN sed -i 's/\r$//' /usr/local/bin/*.sh \
    && chmod +x /usr/local/bin/*.sh \
    && mkdir -p /backups && chown postgres:postgres /backups
USER postgres
# RCLONE_CONFIG=/dev/null: o destino de fora vem só de variáveis de ambiente (RCLONE_CONFIG_OFFSITE_*), sem arquivo de configuração.
ENV TZ=America/Sao_Paulo BACKUP_DIR=/backups RCLONE_CONFIG=/dev/null
VOLUME ["/backups"]
ENTRYPOINT ["/usr/local/bin/agendador.sh"]
