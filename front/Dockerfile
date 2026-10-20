# Tela (React + Vite) servida pelo Caddy, que também faz o HTTPS e repassa /api para a pedidos-api.
# O contexto de construção é a RAIZ do repositório (precisa de front/ e de deploy/Caddyfile).
FROM node:22-alpine AS build
WORKDIR /app
COPY front/package.json front/package-lock.json ./
RUN npm ci
COPY front/ .
# Em produção a tela chama /api (mesmo endereço): sem CORS e sem endereço fixo de desenvolvimento.
ARG VITE_API_URL=/api
ENV VITE_API_URL=$VITE_API_URL
RUN npm run build

FROM caddy:2-alpine
COPY --from=build /app/dist /srv
COPY deploy/Caddyfile /etc/caddy/Caddyfile
EXPOSE 80 443
